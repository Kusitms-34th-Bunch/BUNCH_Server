package com.bunch.feed

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bunch.domain.BunchReportReason
import com.bunch.bunch.dto.BunchReportRequest
import com.bunch.bunch.dto.FollowDecision
import com.bunch.bunch.service.BunchPublicService
import com.bunch.bunch.service.BunchSocialService
import com.bunch.feed.domain.FeedContentHideRepository
import com.bunch.feed.domain.FeedContentKind
import com.bunch.feed.service.FeedAccessService
import com.bunch.global.exception.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

@SpringBootTest
@ActiveProfiles("test")
class FeedAccessIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var access: FeedAccessService

    @Autowired
    private lateinit var social: BunchSocialService

    @Autowired
    private lateinit var publicService: BunchPublicService

    @Autowired
    private lateinit var hides: FeedContentHideRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @AfterEach
    fun cleanFeedData() {
        jdbc.update("DELETE FROM feed_content_hides")
        jdbc.update("DELETE FROM bundles")
    }

    @Test
    fun `completed active users without memberships explore public bundles but cannot act`() {
        val owner = member()
        val target = publicBunch(owner)
        val bundle = published(target, owner)
        val visitor = member()
        assertThat(access.viewer(visitor).currentBunchId).isNull()
        assertThat(access.visibleBundleIds(visitor, listOf(bundle))).containsExactly(bundle)
        assertThat(access.readPublishedBundle(visitor, bundle).id).isEqualTo(bundle)
        assertError(ErrorCode.CURRENT_BUNCH_REQUIRED) { access.requireInteraction(visitor, bundle) }
        assertError(ErrorCode.SIGNUP_INCOMPLETE) { access.viewer(member(false)) }
        assertError(ErrorCode.SIGNUP_INCOMPLETE) { access.visibleBundleIds(member(false), emptyList()) }
        jdbc.update("UPDATE members SET account_status = 'WITHDRAWN' WHERE id = ?", visitor)
        assertError(ErrorCode.UNAUTHORIZED) { access.readPublishedBundle(visitor, bundle) }
        assertError(ErrorCode.USER_NOT_FOUND) { access.viewer(Long.MAX_VALUE) }
    }

    @Test
    fun `feed excludes every active membership while detail uses current bunch internal rights`() {
        val actor = member()
        val first = publicBunch(actor)
        val second = publicBunch(actor)
        val third = publicBunch(member())
        val firstBundle = published(first, actor)
        val secondBundle = published(second, actor)
        val otherBundle = published(third, actor)
        val privateBundle = published(second, actor, "PRIVATE")
        selectCurrent(actor, second)
        val viewer = access.viewer(actor)
        assertThat(viewer.currentBunchId).isEqualTo(second)
        assertThat(access.visibleBundleIds(actor, listOf(firstBundle, secondBundle, otherBundle)))
            .containsExactly(otherBundle)
        assertThat(access.visibleBundleIds(actor, listOf(firstBundle, secondBundle), forFeed = false))
            .containsExactlyInAnyOrder(firstBundle, secondBundle)
        assertThat(access.readPublishedBundle(actor, privateBundle).id).isEqualTo(privateBundle)
        assertThat(access.requireInteraction(actor, privateBundle).actor.bunch.id).isEqualTo(second)
        selectCurrent(actor, first)
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { access.readPublishedBundle(actor, privateBundle) }
        jdbc.update("UPDATE bunch_memberships SET status = 'LEFT' WHERE bunch_id = ?", second)
        assertThat(access.visibleBundleIds(actor, listOf(secondBundle))).containsExactly(secondBundle)
        jdbc.update("UPDATE bunch_memberships SET status = 'REMOVED' WHERE bunch_id = ?", first)
        assertThat(access.viewer(actor).currentBunchId).isNull()
        assertThat(access.visibleBundleIds(actor, listOf(firstBundle))).containsExactly(firstBundle)
        assertError(ErrorCode.CURRENT_BUNCH_REQUIRED) { access.requireActor(actor) }
    }

    @Test
    fun `server current uses last-used time then membership id and never borrows another membership`() {
        val actor = member()
        val first = create(actor).id
        val second = create(actor).id
        val privateBundle = published(first, actor, "PRIVATE")
        jdbc.update("UPDATE bunch_memberships SET last_used_at = '2026-01-01' WHERE member_id = ?", actor)
        assertThat(access.viewer(actor).currentBunchId).isEqualTo(second)
        assertError(ErrorCode.FORBIDDEN) { access.readPublishedBundle(actor, privateBundle) }
        selectCurrent(actor, first)
        assertThat(access.viewer(actor).currentBunchId).isEqualTo(first)
        assertThat(access.readPublishedBundle(actor, privateBundle).id).isEqualTo(privateBundle)
    }

    @Test
    fun `pending follows do not grant access and approved follows are rechecked after cancellation`() {
        val actor = member()
        val viewer = create(actor).id
        val owner = member()
        val target = create(owner).id
        val bundle = published(target, owner)
        val privateBundle = published(target, owner, "PRIVATE")
        val request = social.follow(actor, viewer, target).followId!!
        assertThat(access.visibleBundleIds(actor, listOf(bundle))).isEmpty()
        assertError(ErrorCode.FORBIDDEN) { access.readPublishedBundle(actor, bundle) }
        social.decide(owner, target, request, FollowDecision.APPROVE)
        assertThat(access.visibleBundleIds(actor, listOf(bundle, privateBundle))).containsExactly(bundle)
        assertThat(access.requireInteraction(actor, bundle).actor.bunch.id).isEqualTo(viewer)
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { access.readPublishedBundle(actor, privateBundle) }
        social.cancel(actor, viewer, target, request)
        assertThat(access.visibleBundleIds(actor, listOf(bundle))).isEmpty()
        assertError(ErrorCode.FORBIDDEN) { access.requireInteraction(actor, bundle) }
        jdbc.update("UPDATE bunches SET visibility = 'PUBLIC' WHERE id = ?", target)
        assertThat(access.readPublishedBundle(actor, bundle).id).isEqualTo(bundle)
        jdbc.update("UPDATE bundles SET visibility = 'PRIVATE' WHERE id = ?", bundle)
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { access.requireInteraction(actor, bundle) }
    }

    @Test
    fun `bilateral blocks remove feed detail and reaction attribution without deleting content`() {
        val actor = member()
        val viewer = publicBunch(actor)
        val owner = member()
        val target = publicBunch(owner)
        val bundle = published(target, owner)
        social.block(owner, target, viewer)
        assertThat(access.visibleBundleIds(actor, listOf(bundle))).isEmpty()
        assertThat(access.visibleBunchIds(actor, listOf(target))).isEmpty()
        assertError(ErrorCode.BUNCH_PROFILE_UNAVAILABLE) { access.readPublishedBundle(actor, bundle) }
        assertError(ErrorCode.BUNCH_PROFILE_UNAVAILABLE) { access.requireInteraction(actor, bundle) }
        jdbc.update("DELETE FROM bunch_blocks")
        assertThat(access.readPublishedBundle(actor, bundle).id).isEqualTo(bundle)
        social.block(actor, viewer, target)
        assertThat(access.visibleBundleIds(actor, listOf(bundle))).isEmpty()
        assertError(ErrorCode.BUNCH_PROFILE_UNAVAILABLE) { access.readPublishedBundle(actor, bundle) }
        assertThat(access.readPublishedBundle(member(), bundle).id).isEqualTo(bundle)
    }

    @Test
    fun `existing profile reports hide all target content only for reporting current bunch`() {
        val actor = member()
        val viewer = publicBunch(actor)
        val another = publicBunch(actor)
        val owner = member()
        val target = publicBunch(owner)
        val bundle = published(target, owner)
        selectCurrent(actor, viewer)
        social.report(actor, viewer, BunchReportRequest(target, BunchReportReason.SPAM, null))
        assertThat(access.visibleBundleIds(actor, listOf(bundle))).isEmpty()
        assertThat(access.visibleBunchIds(actor, listOf(target))).isEmpty()
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { access.requireInteraction(actor, bundle) }
        assertThat(access.readPublishedBundle(member(), bundle).id).isEqualTo(bundle)
        selectCurrent(actor, another)
        assertThat(access.readPublishedBundle(actor, bundle).id).isEqualTo(bundle)
        jdbc.update("UPDATE bunch_profile_reports SET status = 'DISMISSED'")
        selectCurrent(actor, viewer)
        assertThat(access.visibleBundleIds(actor, listOf(bundle))).isEmpty()
    }

    @Test
    fun `DB operated hides apply globally and deleting a hide restores next read`() {
        val owner = member()
        val target = publicBunch(owner)
        val bundle = published(target, owner)
        val visitor = member()
        hideGlobally(FeedContentKind.BUNDLE, bundle)
        assertThat(access.visibleBundleIds(visitor, listOf(bundle))).isEmpty()
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { access.readPublishedBundle(visitor, bundle) }
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { access.readPublishedBundle(owner, bundle) }
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { access.requireInteraction(owner, bundle) }
        jdbc.update("DELETE FROM feed_content_hides")
        assertThat(access.readPublishedBundle(visitor, bundle).id).isEqualTo(bundle)
        hideGlobally(FeedContentKind.BUNCH, target)
        assertThat(access.visibleBunchIds(visitor, listOf(target))).isEmpty()
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { access.readPublishedBundle(visitor, bundle) }
        assertThat(count("bundles")).isEqualTo(1)
    }

    @Test
    fun `reporter suppression is shared by active members not other bunches or guest exploration`() {
        val actor = member()
        val viewer = publicBunch(actor)
        val colleague = member()
        join(colleague, issue(actor, viewer))
        val owner = member()
        val target = publicBunch(owner)
        val bundle = published(target, owner)
        TransactionTemplate(transactionManager).executeWithoutResult {
            hides.hideForReporter(FeedContentKind.BUNDLE, bundle, viewer)
        }
        listOf(actor, colleague).forEach {
            assertThat(access.visibleBundleIds(it, listOf(bundle))).isEmpty()
            assertError(ErrorCode.RESOURCE_NOT_FOUND) { access.readPublishedBundle(it, bundle) }
            assertThat(access.requireBundleReportTarget(it, bundle).bundle.id).isEqualTo(bundle)
        }
        hideGlobally(FeedContentKind.BUNDLE, bundle)
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { access.requireBundleReportTarget(actor, bundle) }
        jdbc.update("DELETE FROM feed_content_hides WHERE reporter_bunch_id IS NULL")
        assertThat(access.readPublishedBundle(member(), bundle).id).isEqualTo(bundle)
        assertThat(access.readPublishedBundle(owner, bundle).id).isEqualTo(bundle)
        create(actor)
        assertThat(access.readPublishedBundle(actor, bundle).id).isEqualTo(bundle)
    }

    @Test
    fun `paged existing published reads filter before limits and totals so hidden rows leave no holes`() {
        val owner = member()
        val target = publicBunch(owner)
        val first = published(target, owner)
        val hidden = published(target, owner)
        val last = published(target, owner)
        val privateBundle = published(target, owner, "PRIVATE")
        val visitor = member()
        hideGlobally(FeedContentKind.BUNDLE, hidden)
        val page = publicService.bundles(visitor, target, 0, 1)
        assertThat(page.totalElements).isEqualTo(2)
        assertThat(page.hasNext).isTrue()
        assertThat(page.items.map { it.id }).containsExactly(last)
        assertThat(publicService.bundles(visitor, target, 1, 1).items.map { it.id }).containsExactly(first)
        assertThat(publicService.bundles(visitor, target, 1, 1).hasNext).isFalse()
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { publicService.bundle(visitor, target, hidden) }
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { publicService.bundle(visitor, target, privateBundle) }
        assertThat(publicService.bundles(owner, target, 0, 10).items.map { it.id })
            .containsExactly(privateBundle, last, first)
        hideGlobally(FeedContentKind.BUNCH, target)
        assertThat(access.publishedBundles(visitor, target, PageRequest.of(0, 10)).totalElements).isZero()
    }

    @Test
    fun `draft deleted and invalid candidates cannot gain published access`() {
        val owner = member()
        val target = publicBunch(owner)
        val draft = published(target, owner)
        jdbc.update("UPDATE bundles SET status = 'DRAFT', published_at = NULL WHERE id = ?", draft)
        assertThat(access.visibleBundleIds(owner, listOf(draft), forFeed = false)).isEmpty()
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { access.requireInteraction(owner, draft) }
        hideGlobally(FeedContentKind.BUNDLE, draft)
        jdbc.update("DELETE FROM bundles WHERE id = ?", draft)
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { access.readPublishedBundle(owner, draft) }
        assertThat(jdbc.queryForList(
            "SELECT target_id FROM feed_content_hides WHERE target_kind = 'BUNDLE' AND reporter_bunch_id IS NULL",
            Long::class.java
        )).containsExactly(draft)
        assertError(ErrorCode.INVALID_INPUT) { access.readPublishedBundle(owner, 0) }
        assertError(ErrorCode.INVALID_INPUT) { access.visibleBundleIds(owner, listOf(-1)) }
        assertError(ErrorCode.INVALID_INPUT) { access.visibleBundleIds(owner, List(501) { 1L }) }
        assertThat(access.visibleBundleIds(owner, emptyList())).isEmpty()
        assertThat(access.visibleBunchIds(owner, emptyList())).isEmpty()
    }

    @Test
    fun `private reaction actor remains visible without following but blocks and reports filter it`() {
        val actor = member()
        val viewer = create(actor).id
        val other = create(member()).id
        assertThat(access.visibleBunchIds(actor, listOf(other))).containsExactly(other)
        social.report(actor, viewer, BunchReportRequest(other, BunchReportReason.SPAM, null))
        assertThat(access.visibleBunchIds(actor, listOf(other))).isEmpty()
    }

    @Test
    fun `simultaneous reporter hides are idempotent and join caller rollback`() {
        val actor = member()
        val viewer = create(actor).id
        val bundle = published(viewer, actor)
        val results = concurrently(listOf(
            { TransactionTemplate(transactionManager).execute {
                hides.hideForReporter(FeedContentKind.BUNDLE, bundle, viewer)
            } },
            { TransactionTemplate(transactionManager).execute {
                hides.hideForReporter(FeedContentKind.BUNDLE, bundle, viewer)
            } }
        )).map { it.getOrThrow()!! }
        assertThat(results.sum()).isEqualTo(1)
        assertThat(count("feed_content_hides")).isEqualTo(1)
        assertThatThrownBy {
            TransactionTemplate(transactionManager).executeWithoutResult {
                hides.hideForReporter(FeedContentKind.COMMENT, 100, viewer)
                error("report failed")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM feed_content_hides WHERE target_kind = 'COMMENT' AND target_id = 100",
            Long::class.java
        )).isZero()
        assertThatThrownBy { hides.hideForReporter(FeedContentKind.COMMENT, 100, viewer) }
            .isInstanceOf(IllegalTransactionStateException::class.java)
    }

    @Test
    fun `comment hide namespace reporter scopes and global NULL uniqueness are enforced in PostgreSQL`() {
        val actor = member()
        val viewer = create(actor).id
        val other = create(member()).id
        hideGlobally(FeedContentKind.COMMENT, 100)
        assertThatThrownBy { hideGlobally(FeedContentKind.COMMENT, 100) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        TransactionTemplate(transactionManager).executeWithoutResult {
            hides.hideForReporter(FeedContentKind.COMMENT, 101, viewer)
            hides.hideForReporter(FeedContentKind.COMMENT, 101, other)
            hides.hideForReporter(FeedContentKind.BUNDLE, 101, viewer)
        }
        assertThat(jdbc.queryForList(
            "SELECT target_id FROM feed_content_hides WHERE target_kind = 'COMMENT' AND reporter_bunch_id IS NULL",
            Long::class.java
        )).containsExactly(100L)
        assertThat(jdbc.queryForList(
            "SELECT reporter_bunch_id FROM feed_content_hides WHERE target_kind = 'COMMENT' AND target_id = 101",
            Long::class.java
        )).containsExactlyInAnyOrder(viewer, other)
        assertThat(jdbc.queryForList(
            "SELECT reporter_bunch_id FROM feed_content_hides WHERE target_kind = 'BUNDLE' AND target_id = 101",
            Long::class.java
        )).containsExactly(viewer)
        assertThatThrownBy { hideGlobally(FeedContentKind.BUNDLE, 0) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        jdbc.update("DELETE FROM bunches WHERE id = ?", viewer)
        assertThat(count("feed_content_hides")).isEqualTo(2)
        assertThat(jdbc.queryForList(
            "SELECT target_id, reporter_bunch_id FROM feed_content_hides ORDER BY target_id"
        )).containsExactly(
            mapOf("target_id" to 100L, "reporter_bunch_id" to null),
            mapOf("target_id" to 101L, "reporter_bunch_id" to other)
        )
    }

    private fun publicBunch(actor: Long): Long = create(actor).id.also {
        jdbc.update("UPDATE bunches SET visibility = 'PUBLIC' WHERE id = ?", it)
    }

    private fun published(bunchId: Long, owner: Long, visibility: String = "PUBLIC"): Long = jdbc.queryForObject(
        """INSERT INTO bundles (bunch_id, created_by, status, title, visibility, created_at, updated_at, published_at)
            VALUES (?, ?, 'PUBLISHED', 'feed bundle', ?, '2026-01-01', '2026-01-01', '2026-01-01') RETURNING id""",
        Long::class.java,
        bunchId,
        owner,
        visibility
    )!!

    private fun selectCurrent(actor: Long, bunchId: Long) {
        jdbc.update("UPDATE bunch_memberships SET last_used_at = '2026-01-01' WHERE member_id = ?", actor)
        jdbc.update(
            "UPDATE bunch_memberships SET last_used_at = ? WHERE member_id = ? AND bunch_id = ?",
            java.sql.Timestamp.from(Instant.parse("2026-01-02T00:00:00Z")),
            actor,
            bunchId
        )
    }

    private fun hideGlobally(kind: FeedContentKind, target: Long) {
        jdbc.update(
            "INSERT INTO feed_content_hides (target_kind, target_id, reason) VALUES (?, ?, 'operator review')",
            kind.name,
            target
        )
    }
}
