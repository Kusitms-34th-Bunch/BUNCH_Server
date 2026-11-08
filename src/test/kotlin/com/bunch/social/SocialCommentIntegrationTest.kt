package com.bunch.social

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bunch.domain.BunchBlock
import com.bunch.bunch.domain.BunchBlockRepository
import com.bunch.bunch.domain.BunchFollow
import com.bunch.bunch.domain.BunchFollowRepository
import com.bunch.bunch.domain.BunchFollowStatus
import com.bunch.bunch.domain.BunchProfileReport
import com.bunch.bunch.domain.BunchProfileReportRepository
import com.bunch.bunch.domain.BunchReportReason
import com.bunch.bunch.domain.Visibility
import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.domain.BundleParticipant
import com.bunch.bundle.domain.BundleParticipantRepository
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.domain.BundleStatus
import com.bunch.global.exception.ErrorCode
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import com.bunch.notification.domain.NotificationRepository
import com.bunch.notification.domain.NotificationType
import com.bunch.notification.service.NotificationService
import com.bunch.notification.service.PublishedNotificationAccess
import com.bunch.social.domain.SocialCommentRepository
import com.bunch.social.dto.CreateSocialCommentRequest
import com.bunch.social.dto.SocialCommentItem
import com.bunch.social.service.SocialCommentService
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SocialCommentIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var service: SocialCommentService
    @Autowired
    private lateinit var comments: SocialCommentRepository
    @Autowired
    private lateinit var bundles: BundleRepository
    @Autowired
    private lateinit var participants: BundleParticipantRepository
    @Autowired
    private lateinit var blocks: BunchBlockRepository
    @Autowired
    private lateinit var follows: BunchFollowRepository
    @Autowired
    private lateinit var reports: BunchProfileReportRepository
    @Autowired
    private lateinit var notifications: NotificationRepository
    @Autowired
    private lateinit var notificationService: NotificationService
    @Autowired
    private lateinit var notificationAccess: PublishedNotificationAccess
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
    @Autowired
    private lateinit var flyway: Flyway
    @Autowired
    private lateinit var mappings: org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

    private val sessionIds = mutableListOf<Pair<String, Long>>()

    @AfterEach
    fun cleanupCommentFixtures() {
        sessionIds.forEach { (sid, memberId) -> sessions.delete(sid, memberId) }
        sessionIds.clear()
        jdbc.execute("DROP TRIGGER IF EXISTS comment_test_pause ON social_comments")
        jdbc.execute("DROP FUNCTION IF EXISTS comment_test_pause_insert()")
        jdbc.execute("DROP TRIGGER IF EXISTS comment_test_reject ON push_jobs")
        jdbc.execute("DROP FUNCTION IF EXISTS comment_test_reject_push()")
        jdbc.update("DELETE FROM feed_content_hides WHERE target_kind = 'COMMENT'")
    }

    @Test
    fun `personal attribution snapshot badge and normalized replay stay separate from participant notes`() {
        val owner = member()
        val source = publicBunch(owner)
        val bundle = published(owner, source)
        jdbc.update("""INSERT INTO bundle_comments(bundle_id,member_id,content,version,created_at,updated_at)
            VALUES (?,?,'참여자 코멘트',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)""", bundle.id, owner)
        val key = UUID.randomUUID()
        val first = service.create(owner, bundle.id, key, CreateSocialCommentRequest("  첫줄\n둘째줄  "))
        val replay = service.create(owner, bundle.id, key, CreateSocialCommentRequest("첫줄\n둘째줄"))
        assertThat(replay.id).isEqualTo(first.id)
        assertThat(first.content).isEqualTo("첫줄\n둘째줄")
        assertThat(first.author.memberId).isEqualTo(owner)
        assertThat(first.badge!!.bunchId).isEqualTo(source)
        jdbc.update("UPDATE bunches SET name = ? WHERE id = ?", uniqueName(), source)
        jdbc.update("UPDATE members SET nickname = ? WHERE id = ?", "새닉네임", owner)
        val loaded = service.list(owner, bundle.id).items.single()
        assertThat(loaded.author.nickname).isEqualTo("새닉네임")
        assertThat(loaded.badge).isEqualTo(first.badge)
        assertThat(count("social_comments")).isEqualTo(1)
        assertThat(count("bundle_comments")).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT content FROM bundle_comments WHERE bundle_id = ?",
            String::class.java, bundle.id)).isEqualTo("참여자 코멘트")
        assertThat(service.counts(owner, listOf(bundle.id))).containsEntry(bundle.id, 1L)
        assertError(ErrorCode.IDEMPOTENCY_CONFLICT) {
            service.create(owner, bundle.id, key, CreateSocialCommentRequest("다른 내용"))
        }
    }

    @Test
    fun `only individual author deletes while placeholders and all replies remain counted`() {
        val author = member()
        val source = publicBunch(author)
        val admin = member()
        val target = publicBunch(admin)
        val bundle = published(admin, target)
        val root = write(author, bundle.id)
        val reply = write(admin, bundle.id, root.id)
        assertError(ErrorCode.FORBIDDEN) { service.delete(admin, bundle.id, root.id) }
        val deleted = service.delete(author, bundle.id, root.id)
        assertThat(deleted.deleted).isTrue()
        assertThat(deleted.content).isEqualTo("삭제된 댓글입니다")
        assertThat(deleted.author.nickname).isEqualTo("작성자")
        assertThat(deleted.author.memberId).isNull()
        assertThat(deleted.badge).isNull()
        assertThat(deleted.createdAt).isNull()
        assertThat(deleted.canReply).isFalse()
        assertThat(service.delete(author, bundle.id, root.id)).isEqualTo(deleted)
        assertThat(service.list(admin, bundle.id).items.single().replyCount).isEqualTo(1)
        assertThat(service.list(admin, bundle.id, root.id).items.single().id).isEqualTo(reply.id)
        service.delete(admin, bundle.id, reply.id)
        assertThat(service.counts(author, listOf(bundle.id))).containsEntry(bundle.id, 2L)
        assertThat(service.list(author, bundle.id).items.single().replyCount).isEqualTo(1)
        assertThat(comments.findById(root.id).orElseThrow().content).isEmpty()
        assertThat(source).isPositive()
    }

    @Test
    fun `delete replay never resurrects and deleted parent nesting and foreign parent are rejected`() {
        val author = member()
        val bunch = publicBunch(author)
        val bundle = published(author, bunch)
        val key = UUID.randomUUID()
        val root = service.create(author, bundle.id, key, CreateSocialCommentRequest("본문"))
        val reply = write(author, bundle.id, root.id)
        assertError(ErrorCode.BUNDLE_STATE_CONFLICT) { write(author, bundle.id, reply.id) }
        val other = published(author, bunch)
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { write(author, other.id, root.id) }
        service.delete(author, bundle.id, root.id)
        assertError(ErrorCode.BUNDLE_STATE_CONFLICT) { write(author, bundle.id, root.id) }
        assertThat(service.create(author, bundle.id, key, CreateSocialCommentRequest("본문")).deleted).isTrue()
        assertThat(count("social_comments")).isEqualTo(2)
    }

    @Test
    fun `concurrent idempotent requests persist exactly one comment notification and device job`() {
        val actor = member()
        publicBunch(actor)
        val owner = member()
        val bundle = published(owner, publicBunch(owner))
        participant(bundle.id, owner)
        device(owner)
        val key = UUID.randomUUID()
        val results = concurrently(List(6) {
            { service.create(actor, bundle.id, key, CreateSocialCommentRequest("동시 등록")) }
        })
        assertThat(results.map { it.getOrThrow().id }.distinct()).hasSize(1)
        assertThat(count("social_comments")).isEqualTo(1)
        assertThat(socialNotifications()).hasSize(1)
        assertThat(count("push_jobs")).isEqualTo(1)
    }

    @Test
    fun `root and reply cursors have stable opposite orders and never omit tied timestamps`() {
        val author = member()
        val bundle = published(author, publicBunch(author))
        val roots = List(5) { write(author, bundle.id).id }
        val replies = List(5) { write(author, bundle.id, roots.first()).id }
        jdbc.update("UPDATE social_comments SET created_at = '2026-01-01T00:00:00Z'")
        fun readAll(parent: Long?): List<Long> {
            val ids = mutableListOf<Long>()
            var cursor: String? = null
            do {
                val page = service.list(author, bundle.id, parent, 2, cursor)
                ids += page.items.map { it.id }
                assertThat(page.totalCount).isEqualTo(10)
                assertThat(page.hasNext).isEqualTo(page.nextCursor != null)
                cursor = page.nextCursor
            } while (cursor != null)
            return ids
        }
        assertThat(readAll(null)).containsExactlyElementsOf(roots.reversed())
        assertThat(readAll(roots.first())).containsExactlyElementsOf(replies)
        assertError(ErrorCode.INVALID_INPUT) { service.list(author, bundle.id, size = 51) }
        assertError(ErrorCode.INVALID_INPUT) { service.list(author, bundle.id, cursor = "broken") }
        val cursor = service.list(author, bundle.id, size = 1).nextCursor!!
        assertError(ErrorCode.INVALID_INPUT) { service.list(author, bundle.id, roots.first(), cursor = cursor) }
        val secondBunch = publicBunch(author)
        jdbc.update("UPDATE bunch_memberships SET last_used_at = CURRENT_TIMESTAMP + INTERVAL '1 day' " +
            "WHERE member_id = ? AND bunch_id = ?", author, secondBunch)
        assertError(ErrorCode.INVALID_INPUT) { service.list(author, bundle.id, cursor = cursor) }
    }

    @Test
    fun `bidirectional block hides root subtree before limit and restores original counts on unblock`() {
        val owner = member()
        val target = publicBunch(owner)
        val bundle = published(owner, target)
        val actor = member()
        val source = publicBunch(actor)
        val visible = write(owner, bundle.id)
        val hidden = write(actor, bundle.id)
        write(owner, bundle.id, hidden.id)
        val visibleReply = write(actor, bundle.id, visible.id)
        val block = blocks.saveAndFlush(BunchBlock(blockerBunchId = source, blockedBunchId = target,
            createdByMemberId = actor, createdAt = Instant.now()))
        val page = service.list(owner, bundle.id, size = 1)
        assertThat(page.items.map { it.id }).containsExactly(visible.id)
        assertThat(page.hasNext).isFalse()
        assertThat(page.totalCount).isEqualTo(1)
        assertThat(page.items.single().replyCount).isZero()
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { service.list(owner, bundle.id, hidden.id) }
        blocks.deleteById(block.id)
        assertThat(service.counts(owner, listOf(bundle.id))).containsEntry(bundle.id, 4L)
        assertThat(service.list(owner, bundle.id, visible.id).items.single().id).isEqualTo(visibleReply.id)
    }

    @Test
    fun `comment reporter hide global hide and parent hide use identical list and count predicates`() {
        val owner = member()
        val target = publicBunch(owner)
        val bundle = published(owner, target)
        val actor = member()
        val source = publicBunch(actor)
        val root = write(actor, bundle.id)
        val reply = write(owner, bundle.id, root.id)
        hide("COMMENT", reply.id, target)
        assertThat(service.list(owner, bundle.id).items.single().replyCount).isZero()
        assertThat(service.counts(owner, listOf(bundle.id))).containsEntry(bundle.id, 1L)
        assertThat(service.counts(actor, listOf(bundle.id))).containsEntry(bundle.id, 2L)
        hide("COMMENT", root.id, target)
        assertThat(service.list(owner, bundle.id).items).isEmpty()
        assertThat(service.counts(owner, listOf(bundle.id))).containsEntry(bundle.id, 0L)
        hide("COMMENT", root.id)
        assertThat(service.list(actor, bundle.id).items).isEmpty()
        jdbc.update("DELETE FROM feed_content_hides WHERE target_kind = 'COMMENT'")
        hide("BUNCH", source, target)
        assertThat(service.counts(owner, listOf(bundle.id))).containsEntry(bundle.id, 0L)
        jdbc.update("DELETE FROM feed_content_hides WHERE target_kind = 'BUNCH' AND target_id = ?", source)
        reports.saveAndFlush(BunchProfileReport(reporterBunchId = target, targetBunchId = source,
            reportedByMemberId = owner, reason = BunchReportReason.SPAM, detail = null, createdAt = Instant.now()))
        assertThat(service.list(owner, bundle.id).items).isEmpty()
    }

    @Test
    fun `private acting bunch needs no follow and withdrawn author is anonymized`() {
        val owner = member()
        val bundle = published(owner, publicBunch(owner))
        val actor = member()
        val source = create(actor).id
        val root = write(actor, bundle.id)
        assertThat(service.list(owner, bundle.id).items.single().id).isEqualTo(root.id)
        jdbc.update("UPDATE members SET account_status = 'WITHDRAWN' WHERE id = ?", actor)
        val item = service.list(owner, bundle.id).items.single()
        assertThat(item.author.nickname).isEqualTo("작성자")
        assertThat(item.author.memberId).isNull()
        assertThat(item.author.imageUrl).isNull()
        assertThat(item.badge!!.bunchId).isEqualTo(source)
    }

    @Test
    fun `zero memberships allow reads but forbid writes and registration account and access are rechecked`() {
        val owner = member()
        val bunch = publicBunch(owner)
        val bundle = published(owner, bunch)
        val viewer = member()
        assertThat(service.list(viewer, bundle.id).totalCount).isZero()
        assertError(ErrorCode.CURRENT_BUNCH_REQUIRED) { write(viewer, bundle.id) }
        val incomplete = member(completed = false)
        assertError(ErrorCode.SIGNUP_INCOMPLETE) { service.list(incomplete, bundle.id) }
        jdbc.update("UPDATE members SET account_status = 'WITHDRAWN' WHERE id = ?", viewer)
        assertError(ErrorCode.UNAUTHORIZED) { service.list(viewer, bundle.id) }
        val actor = member()
        val source = publicBunch(actor)
        val key = UUID.randomUUID()
        service.create(actor, bundle.id, key, CreateSocialCommentRequest("내용"))
        jdbc.update("UPDATE bundles SET visibility = 'PRIVATE' WHERE id = ?", bundle.id)
        assertError(ErrorCode.RESOURCE_NOT_FOUND) {
            service.create(actor, bundle.id, key, CreateSocialCommentRequest("내용"))
        }
        assertThat(service.counts(actor, listOf(bundle.id))).isEmpty()
        jdbc.update("UPDATE bundles SET visibility = 'PUBLIC' WHERE id = ?", bundle.id)
        jdbc.update("UPDATE bunches SET visibility = 'PRIVATE' WHERE id = ?", bunch)
        assertError(ErrorCode.FORBIDDEN) { write(actor, bundle.id) }
        follows.saveAndFlush(BunchFollow(followerBunchId = source, followedBunchId = bunch,
            status = BunchFollowStatus.APPROVED, createdByMemberId = actor, createdAt = Instant.now(),
            approvedByMemberId = owner, approvedAt = Instant.now()))
        assertThat(write(actor, bundle.id).id).isPositive()
        jdbc.update("UPDATE bundles SET status = 'DRAFT' WHERE id = ?", bundle.id)
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { service.list(actor, bundle.id) }
    }

    @Test
    fun `reply notification uses parent inbox source and actual target while overlapping recipients dedupe`() {
        val owner = member()
        val target = publicBunch(owner)
        val bundle = published(owner, target)
        participant(bundle.id, owner)
        val actor = member()
        val source = publicBunch(actor)
        device(owner)
        device(actor)
        val root = write(owner, bundle.id)
        val reply = write(actor, bundle.id, root.id)
        val event = socialNotifications().single()
        assertThat(event.recipientMemberId).isEqualTo(owner)
        assertThat(event.title).isEqualTo("내 댓글에 답글이 달렸어요")
        assertThat(event.dedupeKey).isEqualTo("SOCIAL_COMMENT_CREATED:${reply.id}")
        assertThat(event.target.bunchId).isEqualTo(target)
        assertThat(event.target.targetBunchId).isEqualTo(target)
        assertThat(event.target.sourceBunchId).isEqualTo(source)
        assertThat(count("push_jobs")).isEqualTo(1)
        val externalRoot = write(actor, bundle.id)
        val externalReply = write(owner, bundle.id, externalRoot.id)
        val external = socialNotifications().single { it.dedupeKey == "SOCIAL_COMMENT_CREATED:${externalReply.id}" }
        assertThat(external.recipientMemberId).isEqualTo(actor)
        assertThat(external.bunchId).isEqualTo(source)
        assertThat(external.target.bunchId).isEqualTo(source)
        assertThat(external.target.targetBunchId).isEqualTo(target)
        assertThat(external.target.sourceBunchId).isEqualTo(target)
        val payload = jdbc.queryForObject("SELECT payload::text FROM push_jobs WHERE notification_id = ?",
            String::class.java, external.id)!!
        assertThat(mapper.readTree(payload).path("target").path("sourceBunchId").asLong()).isEqualTo(target)
    }

    @Test
    fun `comment notification identifies personal author alongside acting bunch badge`() {
        val owner = member()
        val target = publicBunch(owner)
        val bundle = published(owner, target)
        participant(bundle.id, owner)
        device(owner)
        val actor = member()
        val source = publicBunch(actor)
        jdbc.update("UPDATE members SET nickname = '댓글작성자' WHERE id = ?", actor)
        val sourceName = jdbc.queryForObject("SELECT name FROM bunches WHERE id = ?", String::class.java, source)

        write(actor, bundle.id)

        val event = socialNotifications().single()
        assertThat(event.title).isEqualTo("$sourceName 번치 댓글작성자님이 '발행 번들' 번들에 댓글을 남겼어요")
        val payload = jdbc.queryForObject(
            "SELECT payload::text FROM push_jobs WHERE notification_id = ?",
            String::class.java,
            event.id
        )!!
        assertThat(mapper.readTree(payload).path("aps").path("alert").path("title").asText())
            .contains(event.title)
    }

    @Test
    fun `comment and reply notifications flatten line breaks while preserving full comment content`() {
        val owner = member()
        val target = publicBunch(owner)
        val bundle = published(owner, target)
        participant(bundle.id, owner)
        val actor = member()
        val source = publicBunch(actor)
        device(owner)
        device(actor)
        val content = "첫줄\r\n둘째줄\r셋째줄\n" + "가".repeat(60)
        val expectedBody = "첫줄 둘째줄 셋째줄 " + "가".repeat(60)

        val root = service.create(actor, bundle.id, UUID.randomUUID(), CreateSocialCommentRequest(content))
        val reply = service.create(owner, bundle.id, UUID.randomUUID(), CreateSocialCommentRequest(content, root.id))

        assertThat(listOf(root.content, reply.content)).containsExactly(content, content)
        assertThat(service.list(owner, bundle.id).items.single().content).isEqualTo(content)
        assertThat(service.list(owner, bundle.id, root.id).items.single().content).isEqualTo(content)
        assertThat(socialNotifications()).hasSize(2).allSatisfy { event ->
            assertThat(event.body).isEqualTo(expectedBody)
            val payload = jdbc.queryForObject(
                "SELECT payload::text FROM push_jobs WHERE notification_id = ?",
                String::class.java,
                event.id
            )!!
            assertThat(mapper.readTree(payload).path("aps").path("alert").path("body").asText())
                .doesNotContain("\r", "\n")
                .endsWith("…")
        }
        assertThat(notificationService.list(owner, target, 0, 20).items.single().body).isEqualTo(expectedBody)
        assertThat(notificationService.list(actor, source, 0, 20).items.single().body).isEqualTo(expectedBody)
    }

    @Test
    fun `comment specific hide and deletion suppress actual queued notification access`() {
        val owner = member()
        val target = publicBunch(owner)
        val bundle = published(owner, target)
        participant(bundle.id, owner)
        val actor = member()
        publicBunch(actor)
        val root = write(actor, bundle.id)
        val event = socialNotifications().single()
        assertThat(event.target.commentId).isEqualTo(root.id)
        assertThat(notificationService.open(owner, target, event.id).available).isTrue()
        jdbc.update(
            "INSERT INTO feed_content_hides(target_kind,target_id,reporter_bunch_id,reason) " +
                "VALUES ('COMMENT',?,?, 'REPORTED')",
            root.id, target
        )
        assertThat(notificationService.open(owner, target, event.id).available).isFalse()
        assertThat(notificationAccess.canDeliver(target, event.target)).isFalse()
        jdbc.update("DELETE FROM feed_content_hides WHERE target_kind = 'COMMENT'")
        service.delete(actor, bundle.id, root.id)
        assertThat(notificationService.open(owner, target, event.id).available).isFalse()
        assertThat(notificationAccess.canDeliver(target, event.target)).isFalse()
    }

    @Test
    fun `parent hide suppresses reply delivery but parent deletion preserves normal reply`() {
        val owner = member()
        val target = publicBunch(owner)
        val bundle = published(owner, target)
        participant(bundle.id, owner)
        val actor = member()
        val source = publicBunch(actor)
        val root = write(actor, bundle.id)
        val reply = write(owner, bundle.id, root.id)
        val event = socialNotifications().single { it.recipientMemberId == actor }
        assertThat(event.target.commentId).isEqualTo(reply.id)
        jdbc.update(
            "INSERT INTO feed_content_hides(target_kind,target_id,reason) VALUES ('COMMENT',?,'review')",
            root.id
        )
        assertThat(notificationService.open(actor, source, event.id).available).isFalse()
        assertThat(notificationAccess.canDeliver(source, event.target)).isFalse()
        jdbc.update("DELETE FROM feed_content_hides WHERE target_kind = 'COMMENT'")
        service.delete(actor, bundle.id, root.id)
        assertThat(notificationService.open(actor, source, event.id).available).isTrue()
        assertThat(notificationAccess.canDeliver(source, event.target)).isTrue()
    }

    @Test
    fun `own bunch comments do not notify participants and self replies do not notify actor`() {
        val owner = member()
        val bunch = publicBunch(owner)
        val guest = member()
        join(guest, issue(owner, bunch))
        val bundle = published(owner, bunch)
        participant(bundle.id, owner)
        participant(bundle.id, guest)
        val root = write(owner, bundle.id)
        write(owner, bundle.id, root.id)
        assertThat(socialNotifications()).isEmpty()
        write(guest, bundle.id, root.id)
        assertThat(socialNotifications().map { it.recipientMemberId }).containsExactly(owner)
    }

    @Test
    fun `reply follows comment push preference while retaining inbox`() {
        val owner = member()
        val bunch = publicBunch(owner)
        val bundle = published(owner, bunch)
        val root = write(owner, bundle.id)
        device(owner)
        jdbc.update("INSERT INTO push_preferences(member_id,enabled,types,updated_at) " +
            "VALUES (?,true,'{\"SOCIAL_COMMENT_CREATED\":false}'::jsonb,CURRENT_TIMESTAMP)", owner)
        val actor = member()
        publicBunch(actor)
        write(actor, bundle.id, root.id)
        assertThat(socialNotifications()).hasSize(1)
        assertThat(count("push_jobs")).isZero()
    }

    @Test
    fun `push job write failure rolls comment and notification back and permits clean key retry`() {
        val owner = member()
        val bundle = published(owner, publicBunch(owner))
        participant(bundle.id, owner)
        device(owner)
        val actor = member()
        publicBunch(actor)
        val key = UUID.randomUUID()
        jdbc.execute("""CREATE FUNCTION comment_test_reject_push() RETURNS trigger LANGUAGE plpgsql AS
            'BEGIN RAISE EXCEPTION ''injected comment push failure''; END'""")
        jdbc.execute("CREATE TRIGGER comment_test_reject BEFORE INSERT ON push_jobs " +
            "FOR EACH ROW EXECUTE FUNCTION comment_test_reject_push()")
        assertThatThrownBy { service.create(actor, bundle.id, key, CreateSocialCommentRequest("내용")) }
            .isInstanceOf(RuntimeException::class.java)
        assertThat(count("social_comments")).isZero()
        assertThat(socialNotifications()).isEmpty()
        assertThat(count("push_jobs")).isZero()
        jdbc.execute("DROP TRIGGER comment_test_reject ON push_jobs")
        service.create(actor, bundle.id, key, CreateSocialCommentRequest("내용"))
        assertThat(count("social_comments")).isEqualTo(1)
        assertThat(socialNotifications()).hasSize(1)
        assertThat(count("push_jobs")).isEqualTo(1)
    }

    @Test
    fun `reply is accepted if parent is deleted after successful validation before insert`() {
        val owner = member()
        val bundle = published(owner, publicBunch(owner))
        val root = write(owner, bundle.id)
        val actor = member()
        publicBunch(actor)
        jdbc.execute("""CREATE FUNCTION comment_test_pause_insert() RETURNS trigger LANGUAGE plpgsql AS
            'BEGIN IF NEW.parent_id IS NOT NULL THEN PERFORM pg_advisory_xact_lock(909025); END IF;
            RETURN NEW; END'""")
        jdbc.execute("CREATE TRIGGER comment_test_pause BEFORE INSERT ON social_comments " +
            "FOR EACH ROW EXECUTE FUNCTION comment_test_pause_insert()")
        val executor = Executors.newSingleThreadExecutor()
        try {
            dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("SELECT pg_advisory_lock(909025)") }
                val reply = executor.submit<SocialCommentItem> { write(actor, bundle.id, root.id) }
                try {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    var waiting = false
                    while (!waiting && System.nanoTime() < deadline) {
                        waiting = jdbc.queryForObject("""SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                            WHERE datname = current_database() AND wait_event = 'advisory'
                            AND query ILIKE '%social_comments%')""", Boolean::class.java)!!
                        if (!waiting) Thread.sleep(25)
                    }
                    check(waiting) { "reply insert never reached the validation barrier" }
                    assertThat(service.delete(owner, bundle.id, root.id).deleted).isTrue()
                } finally {
                    connection.createStatement().use { it.execute("SELECT pg_advisory_unlock(909025)") }
                }
                assertThat(reply.get(15, TimeUnit.SECONDS).parentId).isEqualTo(root.id)
            }
            assertThat(service.list(owner, bundle.id, root.id).items).hasSize(1)
            assertThat(service.counts(owner, listOf(bundle.id))).containsEntry(bundle.id, 2L)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `report lookup permits reporter re-report hide but not own deleted or globally hidden targets`() {
        val owner = member()
        val target = publicBunch(owner)
        val bundle = published(owner, target)
        val actor = member()
        publicBunch(actor)
        val root = write(actor, bundle.id)
        assertError(ErrorCode.FORBIDDEN) { service.requireReportable(actor, bundle.id, root.id) }
        hide("COMMENT", root.id, target)
        assertThatCode { service.requireReportable(owner, bundle.id, root.id) }.doesNotThrowAnyException()
        hide("COMMENT", root.id)
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { service.requireReportable(owner, bundle.id, root.id) }
        jdbc.update("DELETE FROM feed_content_hides WHERE target_kind = 'COMMENT'")
        service.delete(actor, bundle.id, root.id)
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { service.requireReportable(owner, bundle.id, root.id) }
    }

    @Test
    fun `actual JWT HTTP validates headers body permissions delete and has no edit mapping`() {
        val author = member()
        val bundle = published(author, publicBunch(author))
        val bearer = bearer(author)
        val path = "/api/v1/bundles/${bundle.id}/comments"
        mvc.perform(get(path)).andExpect(status().isUnauthorized)
        mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, bearer)
            .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"내용\"}"))
            .andExpect(status().isBadRequest)
        mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, bearer).header("Idempotency-Key", "invalid")
            .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"내용\"}"))
            .andExpect(status().isBadRequest)
        mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, bearer).header("Idempotency-Key", UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"   \"}"))
            .andExpect(status().isBadRequest)
        val response = mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, bearer)
            .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(CreateSocialCommentRequest("댓글\n두줄"))))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.author.memberId").value(author))
            .andReturn()
        val id = mapper.readTree(response.response.contentAsString).path("data").path("id").asLong()
        mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.totalCount").value(1))
        val controllerMethods = mappings.handlerMethods.filterValues {
            it.beanType == com.bunch.social.controller.SocialCommentController::class.java
        }.keys.flatMap { it.methodsCondition.methods }
        assertThat(controllerMethods).doesNotContain(
            org.springframework.web.bind.annotation.RequestMethod.PATCH,
            org.springframework.web.bind.annotation.RequestMethod.PUT
        )
        mvc.perform(delete("$path/$id").header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.deleted").value(true))
    }

    @Test
    fun `database enforces one-level same-bundle parents flyway replays cleanly and bundle deletion cascades`() {
        val owner = member()
        val bunch = publicBunch(owner)
        val bundle = published(owner, bunch)
        val root = write(owner, bundle.id)
        val reply = write(owner, bundle.id, root.id)
        assertThatThrownBy { jdbc.update("UPDATE social_comments SET parent_id = ? WHERE id = ?", reply.id, root.id) }
            .isInstanceOf(RuntimeException::class.java)
        val other = published(owner, bunch)
        assertThatThrownBy { jdbc.update("UPDATE social_comments SET bundle_id = ? WHERE id = ?", other.id, reply.id) }
            .isInstanceOf(RuntimeException::class.java)
        assertThat(flyway.migrate().migrationsExecuted).isZero()
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue()
        bundles.deleteById(bundle.id)
        assertThat(comments.findAll()).isEmpty()
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { service.list(owner, bundle.id) }
    }

    @Test
    fun `idempotent replay cannot reveal a comment hidden after creation and batch inputs are bounded`() {
        val owner = member()
        val bunch = publicBunch(owner)
        val bundle = published(owner, bunch)
        val key = UUID.randomUUID()
        val root = service.create(owner, bundle.id, key, CreateSocialCommentRequest("본문"))
        hide("COMMENT", root.id)
        assertError(ErrorCode.RESOURCE_NOT_FOUND) {
            service.create(owner, bundle.id, key, CreateSocialCommentRequest("본문"))
        }
        assertError(ErrorCode.INVALID_INPUT) { service.counts(owner, List(501) { bundle.id }) }
        assertError(ErrorCode.INVALID_INPUT) { service.counts(owner, listOf(-1)) }
        assertThat(service.counts(owner, emptyList())).isEmpty()
    }

    @Test
    fun `deleting acting bunch keeps external personal comments but removes badge`() {
        val owner = member()
        val target = publicBunch(owner)
        val bundle = published(owner, target)
        val author = member()
        val source = publicBunch(author)
        val root = write(author, bundle.id)
        write(owner, bundle.id, root.id)
        jdbc.update("DELETE FROM bunches WHERE id = ?", source)
        val loaded = service.list(owner, bundle.id).items.single()
        assertThat(loaded.id).isEqualTo(root.id)
        assertThat(loaded.author.memberId).isEqualTo(author)
        assertThat(loaded.badge).isNull()
        assertThat(service.counts(owner, listOf(bundle.id))).containsEntry(bundle.id, 2L)
    }

    private fun publicBunch(memberId: Long): Long = create(memberId).id.also {
        jdbc.update("UPDATE bunches SET visibility = 'PUBLIC' WHERE id = ?", it)
    }

    private fun published(owner: Long, bunchId: Long): Bundle = bundles.saveAndFlush(
        Bundle(bunchId = bunchId, createdBy = owner, status = BundleStatus.PUBLISHED, title = "발행 번들",
            visibility = Visibility.PUBLIC, createdAt = Instant.now(), publishedAt = Instant.now())
    )

    private fun participant(bundleId: Long, memberId: Long) {
        participants.saveAndFlush(BundleParticipant(bundleId = bundleId, memberId = memberId, joinedAt = Instant.now()))
    }

    private fun write(memberId: Long, bundleId: Long, parentId: Long? = null): SocialCommentItem =
        service.create(memberId, bundleId, UUID.randomUUID(), CreateSocialCommentRequest("댓글", parentId))

    private fun hide(kind: String, id: Long, reporter: Long? = null) {
        jdbc.update("INSERT INTO feed_content_hides(target_kind,target_id,reporter_bunch_id,reason) " +
            "VALUES (?,?,?,'TEST')", kind, id, reporter)
    }

    private fun socialNotifications() = notifications.findAll().filter { it.type == NotificationType.SOCIAL_COMMENT_CREATED }

    private fun device(memberId: Long) {
        val key = UUID.randomUUID()
        jdbc.update("""INSERT INTO push_devices(installation_id,member_id,session_id,environment,topic,
            token_ciphertext,token_hash,authorization_status,registered_at)
            VALUES (?,?,?,'SANDBOX','test.bunch',?,?,'AUTHORIZED',CURRENT_TIMESTAMP)""",
            key, memberId, UUID.randomUUID(), byteArrayOf(1), key.toString().replace("-", "").repeat(2))
    }

    private fun bearer(memberId: Long): String {
        val pair = tokens.generateToken(memberId, "ROLE_USER")
        val sid = tokens.getSessionId(pair.accessToken)
        sessions.save(sid, memberId, pair.refreshToken, tokens.getRefreshTokenValiditySeconds())
        sessionIds += sid to memberId
        return "Bearer ${pair.accessToken}"
    }
}
