package com.bunch.social

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bunch.domain.BunchBlock
import com.bunch.bunch.domain.BunchBlockRepository
import com.bunch.bunch.domain.BunchReportReason
import com.bunch.bunch.domain.Visibility
import com.bunch.bunch.dto.BunchReportRequest
import com.bunch.bunch.service.BunchSocialService
import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.domain.BundleStatus
import com.bunch.feed.domain.FeedContentKind
import com.bunch.feed.service.FeedAccessService
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import com.bunch.notification.dto.NotificationTarget
import com.bunch.notification.service.PublishedNotificationAccess
import com.bunch.social.domain.SocialContentReportRepository
import com.bunch.social.dto.CreateSocialCommentRequest
import com.bunch.social.dto.SocialContentReportRequest
import com.bunch.social.dto.SocialContentReportResult
import com.bunch.social.service.SocialCommentService
import com.bunch.social.service.SocialContentReportService
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SocialContentReportIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var service: SocialContentReportService

    @Autowired
    private lateinit var comments: SocialCommentService

    @Autowired
    private lateinit var reports: SocialContentReportRepository

    @Autowired
    private lateinit var bundles: BundleRepository

    @Autowired
    private lateinit var access: FeedAccessService

    @Autowired
    private lateinit var notifications: PublishedNotificationAccess

    @Autowired
    private lateinit var blocks: BunchBlockRepository

    @Autowired
    private lateinit var profileReports: BunchSocialService

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var tokens: TokenProvider

    @Autowired
    private lateinit var sessions: RefreshTokenRepository

    @Autowired
    private lateinit var dataSource: DataSource

    @MockitoBean
    private lateinit var clock: Clock

    private val start = Instant.parse("2026-11-07T00:00:00.123456Z")
    private val sessionIds = mutableListOf<Pair<String, Long>>()

    @BeforeEach
    fun setTime() = at(start)

    @AfterEach
    fun cleanupReportFixtures() {
        sessionIds.forEach { (sid, member) -> sessions.delete(sid, member) }
        jdbc.execute("DROP TRIGGER IF EXISTS report_test_reject_hide ON feed_content_hides")
        jdbc.execute("DROP TRIGGER IF EXISTS report_test_reject_row ON social_content_reports")
        jdbc.execute("DROP FUNCTION IF EXISTS report_test_reject_write()")
        jdbc.update("DELETE FROM feed_content_hides WHERE target_kind IN ('BUNDLE', 'COMMENT')")
    }

    @Test
    fun `bundle re-report creates independent rows but hides once and third is rejected`() {
        val owner = member()
        val bundle = published(owner, publicBunch(owner))
        val actor = member()
        val reporter = publicBunch(actor)
        val first = service.bundle(actor, bundle.id, reportRequest(BunchReportReason.SPAM, "  first  "))
        val second = service.bundle(actor, bundle.id, reportRequest(BunchReportReason.OTHER, " second "))
        assertThat(second.reportId).isNotEqualTo(first.reportId)
        assertThat(reports.findById(first.reportId).orElseThrow().detail).isEqualTo("first")
        val row = reports.findById(second.reportId).orElseThrow()
        assertThat(row.detail).isEqualTo("second")
        assertThat(row.reportedByMemberId).isEqualTo(actor)
        assertThat(row.reporterBunchId).isEqualTo(reporter)
        assertThat(row.targetKind).isEqualTo(FeedContentKind.BUNDLE)
        assertThat(row.status).isEqualTo("RECEIVED")
        assertError(ErrorCode.BUNCH_REPORT_LIMIT_EXCEEDED) { service.bundle(actor, bundle.id, reportRequest()) }
        assertThat(count("social_content_reports")).isEqualTo(2)
        assertThat(count("feed_content_hides")).isEqualTo(1)
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { access.readPublishedBundle(actor, bundle.id) }
        assertThat(access.readPublishedBundle(owner, bundle.id).id).isEqualTo(bundle.id)
    }

    @Test
    fun `comment and reply re-report use actual comment target access despite own reporter hide`() {
        val owner = member()
        val bundle = published(owner, publicBunch(owner))
        val root = write(owner, bundle.id)
        val reply = write(owner, bundle.id, root)
        val actor = member()
        publicBunch(actor)
        val first = service.comment(actor, bundle.id, reply, reportRequest())
        val second = service.comment(actor, bundle.id, reply, reportRequest())
        assertThat(first.reportId).isNotEqualTo(second.reportId)
        assertError(ErrorCode.BUNCH_REPORT_LIMIT_EXCEEDED) { service.comment(actor, bundle.id, reply, reportRequest()) }
        assertThat(comments.list(actor, bundle.id).items.single().replyCount).isZero()
        assertThat(comments.counts(actor, listOf(bundle.id))).containsEntry(bundle.id, 1L)
        service.comment(actor, bundle.id, root, reportRequest())
        service.comment(actor, bundle.id, root, reportRequest())
        assertError(ErrorCode.BUNCH_REPORT_LIMIT_EXCEEDED) { service.comment(actor, bundle.id, root, reportRequest()) }
        assertThat(comments.list(actor, bundle.id).items).isEmpty()
        assertThat(comments.counts(actor, listOf(bundle.id))).containsEntry(bundle.id, 0L)
        assertThat(comments.counts(owner, listOf(bundle.id))).containsEntry(bundle.id, 2L)
    }

    @Test
    fun `window anchors first success without extension and resets exactly at 24 hours for both kinds`() {
        val owner = member()
        val bundle = published(owner, publicBunch(owner))
        val commentBundle = published(owner, bundle.bunchId)
        val root = write(owner, commentBundle.id)
        val actor = member()
        publicBunch(actor)
        val bundleAction = { service.bundle(actor, bundle.id, reportRequest()) }
        val commentAction = { service.comment(actor, commentBundle.id, root, reportRequest()) }
        val first = listOf(bundleAction(), commentAction())
        at(start.plusSeconds(86400).minusNanos(1000))
        val second = listOf(bundleAction(), commentAction())
        (first + second).forEach { assertThat(row(it).windowStartedAt).isEqualTo(start) }
        assertError(ErrorCode.BUNCH_REPORT_LIMIT_EXCEEDED) { bundleAction() }
        assertError(ErrorCode.BUNCH_REPORT_LIMIT_EXCEEDED) { commentAction() }
        at(start.plusSeconds(86400))
        val third = listOf(bundleAction(), commentAction())
        third.forEach { assertThat(row(it).windowStartedAt).isEqualTo(start.plusSeconds(86400)) }
        at(start.plusSeconds(86400 * 4 + 17))
        val fourth = bundleAction()
        assertThat(row(fourth).windowStartedAt).isEqualTo(start.plusSeconds(86400 * 4 + 17))
    }

    @Test
    fun `database microseconds and independent reviewed states preserve quota`() {
        val owner = member()
        val bundle = published(owner, publicBunch(owner))
        val actor = member()
        publicBunch(actor)
        at(start.plusNanos(789))
        val first = service.bundle(actor, bundle.id, reportRequest())
        jdbc.update("UPDATE social_content_reports SET status = 'DISMISSED', reviewed_at = ? WHERE id = ?",
            Timestamp.from(start.plusSeconds(10)), first.reportId)
        val second = service.bundle(actor, bundle.id, reportRequest())
        assertThat(row(first).createdAt).isEqualTo(start)
        assertThat(row(first).status).isEqualTo("DISMISSED")
        assertThat(row(first).reviewedAt).isEqualTo(start.plusSeconds(10))
        assertThat(row(second).status).isEqualTo("RECEIVED")
        assertThat(row(second).reviewedAt).isNull()
        assertError(ErrorCode.BUNCH_REPORT_LIMIT_EXCEEDED) { service.bundle(actor, bundle.id, reportRequest()) }
    }

    @Test
    fun `simultaneous members of one bunch atomically share two bundle slots`() {
        concurrentQuota(comment = false)
    }

    @Test
    fun `simultaneous members of one bunch atomically share two comment slots`() {
        concurrentQuota(comment = true)
    }

    @Test
    fun `same numeric ID of bundle and comment has independent quota and other reporters are independent`() {
        val owner = member()
        val bundle = published(owner, publicBunch(owner))
        val root = write(owner, bundle.id)
        // A fresh target shares its ID across the two real ID spaces; never reset a sequence.
        jdbc.update("UPDATE social_comments SET id = ? WHERE id = ?", bundle.id, root)
        val actors = List(2) { member().also(::publicBunch) }
        actors.forEach { actor ->
            repeat(2) { service.comment(actor, bundle.id, bundle.id, reportRequest()) }
            repeat(2) { service.bundle(actor, bundle.id, reportRequest()) }
            assertError(ErrorCode.BUNCH_REPORT_LIMIT_EXCEEDED) { service.bundle(actor, bundle.id, reportRequest()) }
        }
        assertThat(count("social_content_reports")).isEqualTo(8)
    }

    @Test
    fun `profile and content quotas are independent and existing profile API service remains usable`() {
        val owner = member()
        val target = publicBunch(owner)
        val bundle = published(owner, target)
        val actor = member()
        val reporter = publicBunch(actor)
        repeat(2) { service.bundle(actor, bundle.id, reportRequest()) }
        val first = profileReports.report(actor, reporter, BunchReportRequest(target, BunchReportReason.SPAM))
        val second = profileReports.report(actor, reporter, BunchReportRequest(target, BunchReportReason.SPAM))
        assertThat(second.reportId).isNotEqualTo(first.reportId)
        assertThat(count("bunch_profile_reports")).isEqualTo(2)
        assertError(ErrorCode.BUNCH_REPORT_LIMIT_EXCEEDED) {
            profileReports.report(actor, reporter, BunchReportRequest(target, BunchReportReason.SPAM))
        }
    }

    @Test
    fun `own bundle owner forbidden while co-owner and member permitted and same bunch author checked personally`() {
        val owner = member()
        val target = publicBunch(owner)
        val bundle = published(owner, target, Visibility.PRIVATE)
        val guest = member()
        join(guest, issue(owner, target))
        assertError(ErrorCode.FORBIDDEN) { service.bundle(owner, bundle.id, reportRequest()) }
        jdbc.update("UPDATE bunch_memberships SET role = 'CO_OWNER' WHERE member_id = ? AND bunch_id = ?", guest, target)
        service.bundle(guest, bundle.id, reportRequest())
        jdbc.update("UPDATE bunch_memberships SET role = 'MEMBER' WHERE member_id = ? AND bunch_id = ?", guest, target)
        service.bundle(guest, bundle.id, reportRequest())
        assertThat(count("social_content_reports")).isEqualTo(2)
        // Bundle reporter hide legitimately prevents a separate comment action on the hidden bundle.
        jdbc.update("DELETE FROM feed_content_hides WHERE target_kind = 'BUNDLE'")
        val root = write(owner, bundle.id)
        assertError(ErrorCode.FORBIDDEN) { service.comment(owner, bundle.id, root, reportRequest()) }
        service.comment(guest, bundle.id, root, reportRequest())
    }

    @Test
    fun `own comment cannot be reported from another current bunch`() {
        val owner = member()
        val first = publicBunch(owner)
        val bundle = published(owner, first)
        val root = write(owner, bundle.id)
        publicBunch(owner)
        assertError(ErrorCode.FORBIDDEN) { service.comment(owner, bundle.id, root, reportRequest()) }
        assertThat(count("social_content_reports")).isZero()
    }

    @Test
    fun `both directional bundle blocks take precedence over exhausted quota and unblock does not reset`() {
        val owner = member()
        val target = publicBunch(owner)
        val bundle = published(owner, target)
        val actor = member()
        val reporter = publicBunch(actor)
        repeat(2) { service.bundle(actor, bundle.id, reportRequest()) }
        for ((from, to) in listOf(reporter to target, target to reporter)) {
            val block = blocks.saveAndFlush(BunchBlock(blockerBunchId = from, blockedBunchId = to,
                createdByMemberId = actor, createdAt = start))
            assertError(ErrorCode.BUNCH_PROFILE_UNAVAILABLE) { service.bundle(actor, bundle.id, reportRequest()) }
            blocks.deleteById(block.id)
            assertError(ErrorCode.BUNCH_REPORT_LIMIT_EXCEEDED) { service.bundle(actor, bundle.id, reportRequest()) }
        }
        assertThat(count("social_content_reports")).isEqualTo(2)
    }

    @Test
    fun `comment author block is checked before exhausted quota without resetting it`() {
        val owner = member()
        val bundle = published(owner, publicBunch(owner))
        val author = member()
        val source = publicBunch(author)
        val root = write(author, bundle.id)
        val actor = member()
        val reporter = publicBunch(actor)
        repeat(2) { service.comment(actor, bundle.id, root, reportRequest()) }
        for ((from, to) in listOf(reporter to source, source to reporter)) {
            val block = blocks.saveAndFlush(BunchBlock(blockerBunchId = from, blockedBunchId = to,
                createdByMemberId = actor, createdAt = start))
            assertError(ErrorCode.RESOURCE_NOT_FOUND) { service.comment(actor, bundle.id, root, reportRequest()) }
            blocks.deleteById(block.id)
            assertError(ErrorCode.BUNCH_REPORT_LIMIT_EXCEEDED) { service.comment(actor, bundle.id, root, reportRequest()) }
        }
    }

    @Test
    fun `other requires trimmed detail and accepts 200 graphemes preserving inner whitespace`() {
        val owner = member()
        val bundle = published(owner, publicBunch(owner))
        val actor = member()
        publicBunch(actor)
        for (value in listOf(null, "", " \t\n ", "x".repeat(201))) {
            assertError(ErrorCode.INVALID_INPUT) { service.bundle(actor, bundle.id, reportRequest(BunchReportReason.OTHER, value)) }
        }
        assertError(ErrorCode.INVALID_INPUT) { service.bundle(actor, bundle.id, reportRequest(detail = "x".repeat(201))) }
        val unicode = "👨‍👩‍👧‍👦".repeat(200)
        val first = service.bundle(actor, bundle.id, reportRequest(BunchReportReason.OTHER, " $unicode "))
        assertThat(row(first).detail).isEqualTo(unicode)
        val second = service.bundle(actor, bundle.id, reportRequest(BunchReportReason.OTHER, " \n첫줄\n 둘째줄 \t"))
        assertThat(row(second).detail).isEqualTo("첫줄\n 둘째줄")
        assertThat(count("social_content_reports")).isEqualTo(2)
    }

    @Test
    fun `all five reasons retain their values and optional blank detail normalizes to null`() {
        val owner = member()
        val bunch = publicBunch(owner)
        val actor = member()
        publicBunch(actor)
        BunchReportReason.entries.forEach { reason ->
            val bundle = published(owner, bunch)
            val detail = if (reason == BunchReportReason.OTHER) "상세" else "  \n "
            val result = service.bundle(actor, bundle.id, reportRequest(reason, detail))
            assertThat(row(result).reason).isEqualTo(reason)
            assertThat(row(result).detail).isEqualTo(if (reason == BunchReportReason.OTHER) "상세" else null)
        }
    }

    @Test
    fun `account signup and current membership are required and failed attempts consume no quota`() {
        val owner = member()
        val bundle = published(owner, publicBunch(owner))
        val noBunch = member()
        assertError(ErrorCode.CURRENT_BUNCH_REQUIRED) { service.bundle(noBunch, bundle.id, reportRequest()) }
        val incomplete = member(completed = false)
        assertError(ErrorCode.SIGNUP_INCOMPLETE) { service.bundle(incomplete, bundle.id, reportRequest()) }
        val inactive = member()
        publicBunch(inactive)
        for (account in listOf("SUSPENDED", "WITHDRAWN")) {
            jdbc.update("UPDATE members SET account_status = ? WHERE id = ?", account, inactive)
            assertError(ErrorCode.UNAUTHORIZED) { service.bundle(inactive, bundle.id, reportRequest()) }
        }
        assertError(ErrorCode.USER_NOT_FOUND) { service.bundle(Long.MAX_VALUE, bundle.id, reportRequest()) }
        assertThat(count("social_content_reports")).isZero()
    }

    @Test
    fun `deleted draft private and inaccessible targets do not consume quota`() {
        val owner = member()
        val bunch = publicBunch(owner)
        val bundle = published(owner, bunch)
        val root = write(owner, bundle.id)
        val actor = member()
        publicBunch(actor)
        assertError(ErrorCode.INVALID_INPUT) { service.bundle(actor, 0, reportRequest()) }
        assertError(ErrorCode.INVALID_INPUT) { service.comment(actor, bundle.id, -1, reportRequest()) }
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { service.bundle(actor, Long.MAX_VALUE, reportRequest()) }
        val other = published(owner, bunch)
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { service.comment(actor, other.id, root, reportRequest()) }
        jdbc.update("UPDATE bundles SET visibility = 'PRIVATE' WHERE id = ?", bundle.id)
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { service.bundle(actor, bundle.id, reportRequest()) }
        jdbc.update("UPDATE bundles SET visibility = 'PUBLIC', status = 'DRAFT' WHERE id = ?", bundle.id)
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { service.bundle(actor, bundle.id, reportRequest()) }
        jdbc.update("UPDATE bundles SET status = 'PUBLISHED' WHERE id = ?", bundle.id)
        jdbc.update("UPDATE bunches SET visibility = 'PRIVATE' WHERE id = ?", bunch)
        assertError(ErrorCode.FORBIDDEN) { service.bundle(actor, bundle.id, reportRequest()) }
        jdbc.update("UPDATE bunches SET visibility = 'PUBLIC' WHERE id = ?", bunch)
        comments.delete(owner, bundle.id, root)
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { service.comment(actor, bundle.id, root, reportRequest()) }
        bundles.deleteById(bundle.id)
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { service.bundle(actor, bundle.id, reportRequest()) }
        assertThat(count("social_content_reports")).isZero()
    }

    @Test
    fun `global moderation hide is never bypassed and removal preserves reporter suppression`() {
        val owner = member()
        val bundle = published(owner, publicBunch(owner))
        val root = write(owner, bundle.id)
        val actor = member()
        val reporter = publicBunch(actor)
        hide("COMMENT", root)
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { service.comment(actor, bundle.id, root, reportRequest()) }
        jdbc.update("DELETE FROM feed_content_hides WHERE target_kind = 'COMMENT' AND reporter_bunch_id IS NULL")
        service.comment(actor, bundle.id, root, reportRequest())
        hide("COMMENT", root)
        jdbc.update("DELETE FROM feed_content_hides WHERE target_kind = 'COMMENT' AND reporter_bunch_id IS NULL")
        assertThat(comments.list(actor, bundle.id).items).isEmpty()
        assertThat(comments.list(owner, bundle.id).items).hasSize(1)
        hide("BUNDLE", bundle.id)
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { service.bundle(actor, bundle.id, reportRequest()) }
        jdbc.update("DELETE FROM feed_content_hides WHERE target_kind = 'BUNDLE' AND reporter_bunch_id IS NULL")
        service.bundle(actor, bundle.id, reportRequest())
        assertThat(access.visibleBundleIds(actor, listOf(bundle.id), false)).isEmpty()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feed_content_hides WHERE reporter_bunch_id = ?",
            Long::class.java, reporter)).isEqualTo(2)
    }

    @Test
    fun `reported parent suppresses children but actual deleted parent still permits reporting a visible reply`() {
        val owner = member()
        val bundle = published(owner, publicBunch(owner))
        val root = write(owner, bundle.id)
        val reply = write(owner, bundle.id, root)
        val actor = member()
        publicBunch(actor)
        comments.delete(owner, bundle.id, root)
        service.comment(actor, bundle.id, reply, reportRequest())
        hide("COMMENT", root)
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { service.comment(actor, bundle.id, reply, reportRequest()) }
    }

    @Test
    fun `hide failure rolls back first acceptance and retry gets two slots anchored on successful retry`() {
        val owner = member()
        val bundle = published(owner, publicBunch(owner))
        val actor = member()
        publicBunch(actor)
        reject("report_test_reject_hide", "feed_content_hides")
        assertThatThrownBy { service.bundle(actor, bundle.id, reportRequest()) }.isInstanceOf(RuntimeException::class.java)
        assertThat(count("social_content_reports")).isZero()
        assertThat(count("feed_content_hides")).isZero()
        jdbc.execute("DROP TRIGGER report_test_reject_hide ON feed_content_hides")
        at(start.plusSeconds(3600))
        val first = service.bundle(actor, bundle.id, reportRequest())
        service.bundle(actor, bundle.id, reportRequest())
        assertThat(row(first).windowStartedAt).isEqualTo(start.plusSeconds(3600))
        assertError(ErrorCode.BUNCH_REPORT_LIMIT_EXCEEDED) { service.bundle(actor, bundle.id, reportRequest()) }
    }

    @Test
    fun `report insert failure leaves no hide and no consumed quota`() {
        val owner = member()
        val bundle = published(owner, publicBunch(owner))
        val actor = member()
        publicBunch(actor)
        reject("report_test_reject_row", "social_content_reports")
        assertThatThrownBy { service.bundle(actor, bundle.id, reportRequest()) }.isInstanceOf(RuntimeException::class.java)
        assertThat(count("feed_content_hides")).isZero()
        assertThat(count("social_content_reports")).isZero()
        jdbc.execute("DROP TRIGGER report_test_reject_row ON social_content_reports")
        repeat(2) { service.bundle(actor, bundle.id, reportRequest()) }
    }

    @Test
    fun `reported content remains hidden after soft or hard deletion with history retained until reporter deletion`() {
        val owner = member()
        val bundle = published(owner, publicBunch(owner))
        val root = write(owner, bundle.id)
        val actor = member()
        val reporter = publicBunch(actor)
        service.comment(actor, bundle.id, root, reportRequest())
        comments.delete(owner, bundle.id, root)
        assertThat(comments.list(actor, bundle.id).items).isEmpty()
        service.bundle(actor, bundle.id, reportRequest())
        bundles.deleteById(bundle.id)
        assertThat(count("social_content_reports")).isEqualTo(2)
        assertThat(count("feed_content_hides")).isEqualTo(2)
        jdbc.update("DELETE FROM bunches WHERE id = ?", reporter)
        assertThat(count("social_content_reports")).isZero()
        assertThat(count("feed_content_hides")).isZero()
    }

    @Test
    fun `report creates no automatic sanction or notifications and bundle late access reflects reporter hide`() {
        val owner = member()
        val bunch = publicBunch(owner)
        val bundle = published(owner, bunch)
        val actor = member()
        val reporter = publicBunch(actor)
        val root = write(owner, bundle.id)
        val commentTarget = NotificationTarget(
            kind = com.bunch.notification.domain.NotificationTargetKind.BUNDLE_COMMENTS,
            bunchId = reporter,
            bundleId = bundle.id,
            targetBunchId = bunch,
            commentId = root
        )
        assertThat(notifications.canOpen(actor, commentTarget)).isTrue()
        assertThat(notifications.canDeliver(reporter, commentTarget)).isTrue()
        val before = count("notifications")
        service.comment(actor, bundle.id, root, reportRequest())
        assertThat(notifications.canOpen(actor, commentTarget)).isFalse()
        assertThat(notifications.canDeliver(reporter, commentTarget)).isFalse()
        assertThat(access.readPublishedBundle(actor, bundle.id).id).isEqualTo(bundle.id)
        val target = NotificationTarget(
            kind = com.bunch.notification.domain.NotificationTargetKind.BUNDLE,
            bunchId = reporter,
            bundleId = bundle.id,
            targetBunchId = bunch
        )
        assertThat(notifications.canDeliver(reporter, target)).isTrue()
        service.bundle(actor, bundle.id, reportRequest())
        assertThat(notifications.canDeliver(reporter, target)).isFalse()
        assertThat(notifications.canOpen(actor, target)).isFalse()
        assertThat(count("notifications")).isEqualTo(before)
        assertThat(count("bunch_blocks")).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feed_content_hides WHERE reporter_bunch_id IS NULL",
            Long::class.java)).isZero()
        assertThat(access.readPublishedBundle(member(), bundle.id).id).isEqualTo(bundle.id)
    }

    @Test
    fun `role change while waiting for bunch lock is revalidated rather than stale membership accepted`() {
        val owner = member()
        val bunch = publicBunch(owner)
        val bundle = published(owner, bunch)
        val guest = member()
        join(guest, issue(owner, bunch))
        val executor = Executors.newSingleThreadExecutor()
        try {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                connection.prepareStatement("SELECT id FROM bunches WHERE id = ? FOR UPDATE").use {
                    it.setLong(1, bunch)
                    it.executeQuery().close()
                }
                val future = executor.submit<Result<SocialContentReportResult>> {
                    runCatching { service.bundle(guest, bundle.id, reportRequest()) }
                }
                try {
                    awaitLockWait()
                    connection.prepareStatement("UPDATE bunch_memberships SET role = 'OWNER' WHERE member_id = ?").use {
                        it.setLong(1, guest)
                        // Avoid the unique active OWNER constraint while changing the role.
                        connection.prepareStatement("UPDATE bunch_memberships SET role = 'CO_OWNER' WHERE member_id = ?").use { old ->
                            old.setLong(1, owner)
                            old.executeUpdate()
                        }
                        it.executeUpdate()
                    }
                    connection.commit()
                } finally {
                    connection.rollback()
                }
                val error = future.get(15, TimeUnit.SECONDS).exceptionOrNull() as BusinessException
                assertThat(error.errorCode).isEqualTo(ErrorCode.FORBIDDEN)
                assertThat(count("social_content_reports")).isZero()
            }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `real JWT HTTP returns report IDs validates body and forbids third and own author reports`() {
        val owner = member()
        val bundle = published(owner, publicBunch(owner))
        val root = write(owner, bundle.id)
        val actor = member()
        publicBunch(actor)
        val bearer = bearer(actor)
        val path = "/api/v1/bundles/${bundle.id}/reports"
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"SPAM\"}"))
            .andExpect(status().isUnauthorized)
        for (body in listOf("{}", "{\"reason\":null}", "{\"reason\":\"INVALID\"}",
            "{\"reason\":\"OTHER\"}", "{\"reason\":\"OTHER\",\"detail\":\"  \"}")) {
            mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest)
        }
        val ids = List(2) {
            val result = mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(reportRequest())))
                .andExpect(status().isOk).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.reportId").isNumber).andReturn()
            mapper.readTree(result.response.contentAsString).path("data").path("reportId").asLong()
        }
        assertThat(ids.distinct()).hasSize(2)
        mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, bearer)
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(reportRequest())))
            .andExpect(status().isConflict).andExpect(jsonPath("$.error.code").value("BUNCH_011"))
        val commentPath = "/api/v1/bundles/${bundle.id}/comments/$root/reports"
        mvc.perform(post(commentPath).header(HttpHeaders.AUTHORIZATION, bearer(owner))
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(reportRequest())))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.error.code").value("AUTH_002"))
    }

    @Test
    fun `comment HTTP accepts second then rejects third using ordinary JWT and current bunch`() {
        val owner = member()
        val bundle = published(owner, publicBunch(owner))
        val root = write(owner, bundle.id)
        val actor = member()
        publicBunch(actor)
        val bearer = bearer(actor)
        val path = "/api/v1/bundles/${bundle.id}/comments/$root/reports"
        repeat(2) {
            mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"SPAM\"}"))
                .andExpect(status().isOk).andExpect(jsonPath("$.data.reportId").isNumber)
        }
        mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, bearer)
            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"SPAM\"}"))
            .andExpect(status().isConflict).andExpect(jsonPath("$.error.code").value("BUNCH_011"))
        assertThat(count("social_content_reports")).isEqualTo(2)
    }

    private fun concurrentQuota(comment: Boolean) {
        val owner = member()
        val bundle = published(owner, publicBunch(owner))
        val root = write(owner, bundle.id)
        val actor = member()
        val reporter = publicBunch(actor)
        val invitation = issue(actor, reporter)
        val actors = listOf(actor) + List(3) { member().also { join(it, invitation) } }
        val results = concurrently(actors.map { member -> {
            if (comment) service.comment(member, bundle.id, root, reportRequest())
            else service.bundle(member, bundle.id, reportRequest())
        } })
        assertThat(results.count { it.isSuccess }).isEqualTo(2)
        assertThat(results.filter { it.isSuccess }.map { it.getOrThrow().reportId }.distinct()).hasSize(2)
        val failures = results.mapNotNull { it.exceptionOrNull() }
        assertThat(failures).hasSize(2).allSatisfy {
            assertThat(it).isInstanceOf(BusinessException::class.java)
            assertThat((it as BusinessException).errorCode).isEqualTo(ErrorCode.BUNCH_REPORT_LIMIT_EXCEEDED)
        }
        assertThat(count("social_content_reports")).isEqualTo(2)
        assertThat(count("feed_content_hides")).isEqualTo(1)
    }

    private fun awaitLockWait() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val waiting = jdbc.queryForObject("""SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                WHERE datname = current_database() AND wait_event_type = 'Lock'
                AND query LIKE '%bunches%' AND pid <> pg_backend_pid())""", Boolean::class.java)!!
            if (waiting) return
            Thread.sleep(20)
        }
        error("report never reached Bunch lock barrier")
    }

    private fun reportRequest(reason: BunchReportReason = BunchReportReason.SPAM, detail: String? = null) =
        SocialContentReportRequest(reason, detail)

    private fun row(result: SocialContentReportResult) = reports.findById(result.reportId).orElseThrow()

    private fun at(instant: Instant) { `when`(clock.instant()).thenReturn(instant) }

    private fun publicBunch(member: Long): Long = create(member).id.also {
        jdbc.update("UPDATE bunches SET visibility = 'PUBLIC' WHERE id = ?", it)
    }

    private fun published(owner: Long, bunch: Long, visibility: Visibility = Visibility.PUBLIC): Bundle =
        bundles.saveAndFlush(Bundle(bunchId = bunch, createdBy = owner, status = BundleStatus.PUBLISHED,
            title = "신고 검증", visibility = visibility, createdAt = start, publishedAt = start))

    private fun write(author: Long, bundle: Long, parent: Long? = null): Long =
        comments.create(author, bundle, UUID.randomUUID(), CreateSocialCommentRequest("댓글", parent)).id

    private fun hide(kind: String, target: Long) {
        jdbc.update("INSERT INTO feed_content_hides(target_kind,target_id,reason) VALUES (?,?,'OPS')", kind, target)
    }

    private fun reject(trigger: String, table: String) {
        jdbc.execute("""CREATE FUNCTION report_test_reject_write() RETURNS trigger LANGUAGE plpgsql AS
            'BEGIN RAISE EXCEPTION ''injected report write failure''; END'""")
        jdbc.execute("CREATE TRIGGER $trigger BEFORE INSERT ON $table " +
            "FOR EACH ROW EXECUTE FUNCTION report_test_reject_write()")
    }

    private fun bearer(member: Long): String {
        val pair = tokens.generateToken(member, "ROLE_USER")
        val sid = tokens.getSessionId(pair.accessToken)
        sessions.save(sid, member, pair.refreshToken, tokens.getRefreshTokenValiditySeconds())
        sessionIds += sid to member
        return "Bearer ${pair.accessToken}"
    }
}
