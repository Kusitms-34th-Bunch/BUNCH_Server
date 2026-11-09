package com.bunch.social

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bunch.domain.BunchReportReason
import com.bunch.bunch.dto.BunchReportRequest
import com.bunch.bunch.dto.FollowDecision
import com.bunch.bunch.service.BunchSocialService
import com.bunch.feed.service.FeedAccessService
import com.bunch.global.exception.ErrorCode
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import com.bunch.notification.domain.NotificationRepository
import com.bunch.notification.domain.NotificationType
import com.bunch.social.domain.SocialBundleLikeRepository
import com.bunch.social.service.SocialLikeService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SocialLikeIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var likes: SocialLikeService

    @Autowired
    private lateinit var rows: SocialBundleLikeRepository

    @Autowired
    private lateinit var social: BunchSocialService

    @Autowired
    private lateinit var access: FeedAccessService

    @Autowired
    private lateinit var notifications: NotificationRepository

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var tokens: TokenProvider

    @Autowired
    private lateinit var sessions: RefreshTokenRepository

    @Autowired
    private lateinit var transactions: PlatformTransactionManager

    private val ownSessions = mutableListOf<Pair<String, Long>>()

    @AfterEach
    fun cleanSocialData() {
        allowWrites()
        ownSessions.forEach { (sessionId, memberId) -> sessions.delete(sessionId, memberId) }
        ownSessions.clear()
        jdbc.update("DELETE FROM push_jobs")
        jdbc.update("DELETE FROM push_devices")
        jdbc.update("DELETE FROM push_preferences")
        jdbc.update("DELETE FROM feed_content_hides")
        jdbc.update("DELETE FROM bundles")
    }

    @Test
    fun `all roles share one like and another member can cancel on our private bundle`() {
        val owner = member()
        val bunch = create(owner).id
        val coOwner = member()
        val guest = member()
        join(coOwner, issue(owner, bunch))
        join(guest, issue(owner, bunch))
        jdbc.update("UPDATE bunch_memberships SET role = 'CO_OWNER' WHERE member_id = ?", coOwner)
        val bundle = published(bunch, owner, "PRIVATE")

        listOf(owner, coOwner, guest).forEach { actor ->
            assertThat(likes.like(actor, bundle).likedByCurrentBunch).isTrue()
            assertThat(likes.summary(actor, bundle).likeCount).isEqualTo(1)
        }
        val first = rows.findByBunchIdAndBundleId(bunch, bundle)!!
        assertThat(likes.unlike(coOwner, bundle).likeCount).isZero()
        assertThat(likes.unlike(guest, bundle).likedByCurrentBunch).isFalse()
        assertThat(likes.like(guest, bundle).likeCount).isEqualTo(1)
        val after = rows.findByBunchIdAndBundleId(bunch, bundle)!!
        assertThat(after.id).isEqualTo(first.id)
        assertThat(after.firstLikedAt).isEqualTo(first.firstLikedAt)
        assertThat(count("social_bundle_likes")).isEqualTo(1)
        assertThat(likeNotifications()).isEmpty()
    }

    @Test
    fun `absent unlike is a no-op and server current selects the acting bunch`() {
        val actor = member()
        val first = create(actor).id
        val second = create(actor).id
        val owner = member()
        val bundle = published(publicBunch(owner), owner)
        bunches.updateLastUsed(actor, first)
        assertThat(likes.unlike(actor, bundle).likeCount).isZero()
        assertThat(count("social_bundle_likes")).isZero()
        assertThat(likes.like(actor, bundle).currentBunchId).isEqualTo(first)
        bunches.updateLastUsed(actor, second)
        assertThat(likes.summary(actor, bundle).likedByCurrentBunch).isFalse()
        assertThat(likes.like(actor, bundle).likeCount).isEqualTo(2)
        assertThat(likes.unlike(actor, bundle).likeCount).isEqualTo(1)
        assertThat(rows.findByBunchIdAndBundleId(first, bundle)!!.active).isTrue()
    }

    @Test
    fun `zero memberships can read but cannot mutate and unfinished inactive users are rejected`() {
        val owner = member()
        val bundle = published(publicBunch(owner), owner)
        val visitor = member()
        assertThat(likes.summary(visitor, bundle).currentBunchId).isNull()
        assertThat(likes.summaries(visitor, listOf(bundle)).getValue(bundle).likedByCurrentBunch).isFalse()
        assertError(ErrorCode.CURRENT_BUNCH_REQUIRED) { likes.like(visitor, bundle) }
        assertError(ErrorCode.CURRENT_BUNCH_REQUIRED) { likes.unlike(visitor, bundle) }
        assertError(ErrorCode.SIGNUP_INCOMPLETE) { likes.like(member(false), bundle) }
        assertError(ErrorCode.SIGNUP_INCOMPLETE) { likes.summaries(member(false), emptyList()) }
        jdbc.update("UPDATE members SET account_status = 'WITHDRAWN' WHERE id = ?", visitor)
        assertError(ErrorCode.UNAUTHORIZED) { likes.like(visitor, bundle) }
        assertError(ErrorCode.USER_NOT_FOUND) { likes.like(Long.MAX_VALUE, bundle) }
        assertThat(count("social_bundle_likes")).isZero()
    }

    @Test
    fun `private bunch requires approved follow and private bundle remains internal only`() {
        val actor = member()
        val acting = publicBunch(actor)
        val owner = member()
        val target = create(owner).id
        val bundle = published(target, owner)
        assertError(ErrorCode.FORBIDDEN) { likes.like(actor, bundle) }
        val follow = social.follow(actor, acting, target)
        assertError(ErrorCode.FORBIDDEN) { likes.like(actor, bundle) }
        social.decide(owner, target, follow.followId!!, FollowDecision.APPROVE)
        assertThat(likes.like(actor, bundle).likeCount).isEqualTo(1)
        jdbc.update("UPDATE bundles SET visibility = 'PRIVATE' WHERE id = ?", bundle)
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { likes.unlike(actor, bundle) }
        assertThat(rows.findByBunchIdAndBundleId(acting, bundle)!!.active).isTrue()
        // Being a member of the target does not borrow internal rights while another bunch is current.
        join(actor, issue(owner, target))
        bunches.updateLastUsed(actor, acting)
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { likes.like(actor, bundle) }
        bunches.updateLastUsed(actor, target)
        assertThat(likes.like(actor, bundle).currentBunchId).isEqualTo(target)
    }

    @Test
    fun `both directions of block prohibit even idempotent replay without changing the like`() {
        val actor = member()
        val acting = publicBunch(actor)
        val owner = member()
        val target = publicBunch(owner)
        val bundle = published(target, owner)
        likes.like(actor, bundle)
        social.block(actor, acting, target)
        assertError(ErrorCode.BUNCH_PROFILE_UNAVAILABLE) { likes.like(actor, bundle) }
        assertError(ErrorCode.BUNCH_PROFILE_UNAVAILABLE) { likes.unlike(actor, bundle) }
        jdbc.update("DELETE FROM bunch_blocks")
        social.block(owner, target, acting)
        assertError(ErrorCode.BUNCH_PROFILE_UNAVAILABLE) { likes.like(actor, bundle) }
        assertError(ErrorCode.BUNCH_PROFILE_UNAVAILABLE) { likes.summary(actor, bundle) }
        assertThat(rows.findByBunchIdAndBundleId(acting, bundle)!!.active).isTrue()
    }

    @Test
    fun `bundle and bunch hiding and profile reports are rechecked on every request`() {
        val actor = member()
        val acting = publicBunch(actor)
        val owner = member()
        val target = publicBunch(owner)
        val bundle = published(target, owner)
        likes.like(actor, bundle)
        listOf("BUNDLE" to bundle, "BUNCH" to target).forEach { (kind, id) ->
            listOf<Long?>(acting, null).forEach { reporter ->
                hide(kind, id, reporter)
                assertError(ErrorCode.RESOURCE_NOT_FOUND) { likes.unlike(actor, bundle) }
                assertThat(likes.summaries(actor, listOf(bundle))).isEmpty()
                jdbc.update("DELETE FROM feed_content_hides")
            }
        }
        social.report(actor, acting, BunchReportRequest(target, BunchReportReason.SPAM))
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { likes.like(actor, bundle) }
        assertThat(rows.findByBunchIdAndBundleId(acting, bundle)!!.active).isTrue()
    }

    @Test
    fun `counts filter the acting bunch by block report and hides but include unfollowed private actors`() {
        val owner = member()
        val target = publicBunch(owner)
        val bundle = published(target, owner)
        val actors = (1..5).map { member() }
        val sources = actors.map { create(it).id }
        actors.forEach { likes.like(it, bundle) }
        assertThat(likes.summary(owner, bundle).likeCount).isEqualTo(5)
        social.block(owner, target, sources[0])
        social.report(owner, target, BunchReportRequest(sources[1], BunchReportReason.SPAM))
        hide("BUNCH", sources[2], target)
        hide("BUNCH", sources[3], null)
        assertThat(access.visibleBunchIds(owner, sources)).containsExactly(sources[4])
        assertThat(likes.summary(owner, bundle).likeCount).isEqualTo(1)
        assertThat(likes.summary(owner, bundle).likedByCurrentBunch).isFalse()
        val visitor = member()
        assertThat(likes.summary(visitor, bundle).likeCount).isEqualTo(4)
        assertThat(likes.summary(actors[4], bundle).likedByCurrentBunch).isTrue()
        jdbc.update("DELETE FROM bunch_blocks")
        assertThat(likes.summary(owner, bundle).likeCount).isEqualTo(2)
    }

    @Test
    fun `batch summaries zero fill visible targets omit inaccessible ones and enforce the common bounds`() {
        val owner = member()
        val target = publicBunch(owner)
        val visible = published(target, owner)
        val private = published(target, owner, "PRIVATE")
        val visitor = member()
        val result = likes.summaries(visitor, listOf(visible, private, Long.MAX_VALUE))
        assertThat(result.keys).containsExactly(visible)
        assertThat(result.getValue(visible).likeCount).isZero()
        assertThat(likes.summaries(visitor, emptyList())).isEmpty()
        assertError(ErrorCode.INVALID_INPUT) { likes.summaries(visitor, List(501) { visible }) }
        assertError(ErrorCode.INVALID_INPUT) { likes.summaries(visitor, listOf(0)) }
        assertError(ErrorCode.INVALID_INPUT) { likes.like(visitor, 0) }
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { likes.summary(visitor, Long.MAX_VALUE) }
        jdbc.update("UPDATE bundles SET status = 'DRAFT', published_at = NULL WHERE id = ?", visible)
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { likes.summary(visitor, visible) }
    }

    @Test
    fun `concurrent members produce one collective like and one notification per recipient`() {
        val actor = member()
        val acting = publicBunch(actor)
        val other = member()
        join(other, issue(actor, acting))
        val owner = member()
        val bundle = published(publicBunch(owner), owner)
        val results = concurrently(listOf(
            { likes.like(actor, bundle) },
            { likes.like(other, bundle) },
            { likes.like(actor, bundle) },
            { likes.like(other, bundle) }
        ))
        assertThat(results.all { it.isSuccess }).isTrue()
        assertThat(results.map { it.getOrThrow().likeCount }).containsOnly(1L)
        assertThat(count("social_bundle_likes")).isEqualTo(1)
        assertThat(likeNotifications()).hasSize(1)
        assertThat(concurrently(listOf({ likes.unlike(actor, bundle) }, { likes.like(other, bundle) }))
            .all { it.isSuccess }).isTrue()
        assertThat(likeNotifications()).hasSize(1)
        assertThat(count("social_bundle_likes")).isEqualTo(1)
    }

    @Test
    fun `waiting mutation rechecks access after a committed public to private change`() {
        val actor = member()
        val acting = publicBunch(actor)
        val owner = member()
        val target = publicBunch(owner)
        val bundle = published(target, owner)
        val executor = Executors.newSingleThreadExecutor()
        try {
            var future: java.util.concurrent.Future<*>? = null
            TransactionTemplate(transactions).executeWithoutResult {
                jdbc.queryForObject("SELECT id FROM bunches WHERE id = ? FOR UPDATE", Long::class.java, acting)
                future = executor.submit<Unit> { likes.like(actor, bundle) }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (true) {
                    jdbc.execute("SELECT pg_stat_clear_snapshot()")
                    val waiting = jdbc.queryForObject(
                        """SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()
                            AND wait_event_type = 'Lock' AND pid <> pg_backend_pid()""", Long::class.java
                    )!!
                    if (waiting > 0) break
                    check(System.nanoTime() < deadline) { "Like request did not reach the bunch lock" }
                    Thread.sleep(20)
                }
                jdbc.update("UPDATE bunches SET visibility = 'PRIVATE' WHERE id = ?", target)
            }
            assertThatThrownBy { future!!.get(10, TimeUnit.SECONDS) }
                .hasCauseInstanceOf(com.bunch.global.exception.BusinessException::class.java)
            assertThat(count("social_bundle_likes")).isZero()
            assertThat(likeNotifications()).isEmpty()
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `external first like notifies only active participants except actor and writes real device jobs`() {
        val owner = member()
        val target = publicBunch(owner)
        val recipient = member()
        val nonparticipant = member()
        val departed = member()
        val withdrawn = member()
        val actor = member()
        listOf(recipient, nonparticipant, departed, withdrawn, actor).forEach { join(it, issue(owner, target)) }
        val bundle = published(target, owner)
        listOf(recipient, departed, withdrawn, actor).forEach { participant(bundle, it) }
        jdbc.update("UPDATE bunch_memberships SET status = 'LEFT' WHERE member_id = ?", departed)
        jdbc.update("UPDATE members SET account_status = 'WITHDRAWN' WHERE id = ?", withdrawn)
        val acting = publicBunch(actor)
        device(owner)
        device(owner)
        device(recipient)
        jdbc.update(
            "INSERT INTO push_preferences(member_id, enabled, types, updated_at) " +
                "VALUES (?, true, '{\"BUNDLE_LIKED\":false}'::jsonb, CURRENT_TIMESTAMP)", recipient
        )
        likes.like(actor, bundle)
        val inbox = likeNotifications()
        assertThat(inbox.map { it.recipientMemberId }).containsExactlyInAnyOrder(owner, recipient)
        assertThat(inbox.map { it.title }).containsOnly("${bunchName(acting)} 번치가 우리 번들을 좋아해요")
        assertThat(inbox.map { it.body }).containsOnly("기록")
        inbox.forEach {
            assertThat(it.target.sourceBunchId).isEqualTo(acting)
            assertThat(it.target.targetBunchId).isEqualTo(target)
            assertThat(it.target.bunchId).isEqualTo(target)
            assertThat(it.target.bundleId).isEqualTo(bundle)
        }
        assertThat(jdbc.queryForObject(
            """SELECT count(*) FROM push_jobs WHERE type = 'BUNDLE_LIKED'
                AND recipient_member_id = ? AND payload->'target'->>'sourceBunchId' = ?
                AND payload->'target'->>'targetBunchId' = ? AND payload->>'eventId' = id::text""",
            Long::class.java, owner, acting.toString(), target.toString()
        )).isEqualTo(2)
        assertThat(count("push_jobs")).isEqualTo(2)
        jdbc.update("UPDATE bundles SET title = '다른 제목', revision = revision + 1 WHERE id = ?", bundle)
        jdbc.update("UPDATE bunches SET name = ? WHERE id = ?", uniqueName(), acting)
        likes.unlike(actor, bundle)
        likes.like(actor, bundle)
        assertThat(likeNotifications().map { it.id }).containsExactlyElementsOf(inbox.map { it.id })
        assertThat(likeNotifications().map { it.body }).containsOnly("기록")
        assertThat(count("push_jobs")).isEqualTo(2)
    }

    @Test
    fun `first fact survives expired notifications and jobs and does not backfill new devices`() {
        val actor = member()
        publicBunch(actor)
        val owner = member()
        val bundle = published(publicBunch(owner), owner)
        likes.like(actor, bundle)
        assertThat(likeNotifications()).hasSize(1)
        assertThat(count("push_jobs")).isZero()
        device(owner)
        likes.like(actor, bundle)
        assertThat(count("push_jobs")).isZero()
        jdbc.update("DELETE FROM notifications WHERE type = 'BUNDLE_LIKED'")
        likes.unlike(actor, bundle)
        likes.like(actor, bundle)
        assertThat(likeNotifications()).isEmpty()
        assertThat(count("push_jobs")).isZero()
    }

    @Test
    fun `inbox or push failure rolls back first fact and permits exactly one clean retry`() {
        val actor = member()
        val acting = publicBunch(actor)
        val owner = member()
        val bundle = published(publicBunch(owner), owner)
        device(owner)
        listOf("notifications", "push_jobs").forEach { table ->
            rejectWrites(table)
            assertThatThrownBy { likes.like(actor, bundle) }.isInstanceOf(RuntimeException::class.java)
            assertThat(rows.findByBunchIdAndBundleId(acting, bundle)).isNull()
            assertThat(likeNotifications()).isEmpty()
            assertThat(count("push_jobs")).isZero()
            allowWrites()
        }
        likes.like(actor, bundle)
        likes.like(actor, bundle)
        assertThat(likeNotifications()).hasSize(1)
        assertThat(count("push_jobs")).isEqualTo(1)
    }

    @Test
    fun `bundle and acting bunch deletion cascade likes while published edits preserve them`() {
        val actor = member()
        val acting = publicBunch(actor)
        val owner = member()
        val target = publicBunch(owner)
        val first = published(target, owner)
        val second = published(target, owner)
        likes.like(actor, first)
        likes.like(actor, second)
        jdbc.update("UPDATE bundles SET title = '수정', revision = revision + 1 WHERE id = ?", first)
        assertThat(likes.summary(actor, first).likeCount).isEqualTo(1)
        jdbc.update("DELETE FROM bundles WHERE id = ?", first)
        assertThat(count("social_bundle_likes")).isEqualTo(1)
        jdbc.update("DELETE FROM bunches WHERE id = ?", acting)
        assertThat(count("social_bundle_likes")).isZero()
    }

    @Test
    fun `HTTP routes require real sessions return common responses and never trust viewer input`() {
        val actor = member()
        val acting = publicBunch(actor)
        val owner = member()
        val target = publicBunch(owner)
        val bundle = published(target, owner)
        val path = "/api/v1/bundles/$bundle/like"
        mvc.perform(put(path)).andExpect(status().isUnauthorized)
        val bearer = bearer(actor)
        mvc.perform(put(path).header("Authorization", bearer).param("viewerBunchId", target.toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.currentBunchId").value(acting))
            .andExpect(jsonPath("$.data.bundleId").value(bundle))
            .andExpect(jsonPath("$.data.likedByCurrentBunch").value(true))
            .andExpect(jsonPath("$.data.likeCount").value(1))
        mvc.perform(get(path).header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.likeCount").value(1))
        mvc.perform(delete(path).header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.likedByCurrentBunch").value(false))
            .andExpect(jsonPath("$.data.likeCount").value(0))
        mvc.perform(put(path).header("Authorization", bearer(member())))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("BUNCH_010"))
        mvc.perform(put(path).header("Authorization", bearer(member(false))))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("AUTH_015"))
        mvc.perform(get("/api/v1/bundles/0/like").header("Authorization", bearer))
            .andExpect(status().isBadRequest)
    }

    private fun publicBunch(owner: Long): Long = create(owner).id.also {
        jdbc.update("UPDATE bunches SET visibility = 'PUBLIC' WHERE id = ?", it)
    }

    private fun published(bunchId: Long, owner: Long, visibility: String = "PUBLIC"): Long {
        val id = jdbc.queryForObject(
            """INSERT INTO bundles(bunch_id,created_by,status,title,visibility,created_at,updated_at,published_at)
                VALUES (?,?,'PUBLISHED','기록',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                RETURNING id""", Long::class.java, bunchId, owner, visibility
        )!!
        participant(id, owner)
        return id
    }

    private fun participant(bundleId: Long, memberId: Long) {
        jdbc.update(
            "INSERT INTO bundle_participants(bundle_id,member_id,joined_at) VALUES (?,?,CURRENT_TIMESTAMP)",
            bundleId, memberId
        )
    }

    private fun hide(kind: String, targetId: Long, reporter: Long?) {
        jdbc.update(
            "INSERT INTO feed_content_hides(target_kind,target_id,reporter_bunch_id,reason) VALUES (?,?,?,'test')",
            kind, targetId, reporter
        )
    }

    private fun likeNotifications() = notifications.findAll().filter { it.type == NotificationType.BUNDLE_LIKED }

    private fun bunchName(id: Long) =
        jdbc.queryForObject("SELECT name FROM bunches WHERE id = ?", String::class.java, id)

    private fun device(memberId: Long) {
        val installation = UUID.randomUUID()
        jdbc.update(
            """INSERT INTO push_devices(installation_id,member_id,session_id,environment,topic,
                token_ciphertext,token_hash,authorization_status,registered_at)
                VALUES (?,?,?,'SANDBOX','test.bunch',?,?,'AUTHORIZED',CURRENT_TIMESTAMP)""",
            installation, memberId, UUID.randomUUID(), byteArrayOf(1, 2, 3),
            installation.toString().replace("-", "").repeat(2)
        )
    }

    private fun rejectWrites(table: String) {
        jdbc.execute("""CREATE FUNCTION social_like_reject_write() RETURNS trigger LANGUAGE plpgsql AS
            'BEGIN RAISE EXCEPTION ''injected like write failure''; END'""")
        jdbc.execute("CREATE TRIGGER social_like_reject BEFORE INSERT ON $table " +
            "FOR EACH ROW EXECUTE FUNCTION social_like_reject_write()")
    }

    private fun allowWrites() {
        jdbc.execute("DROP TRIGGER IF EXISTS social_like_reject ON notifications")
        jdbc.execute("DROP TRIGGER IF EXISTS social_like_reject ON push_jobs")
        jdbc.execute("DROP FUNCTION IF EXISTS social_like_reject_write()")
    }

    private fun bearer(memberId: Long): String {
        val pair = tokens.generateToken(memberId, "ROLE_USER")
        val sessionId = tokens.getSessionId(pair.accessToken)
        sessions.save(sessionId, memberId, pair.refreshToken, tokens.getRefreshTokenValiditySeconds())
        ownSessions.add(sessionId to memberId)
        return "Bearer ${pair.accessToken}"
    }
}
