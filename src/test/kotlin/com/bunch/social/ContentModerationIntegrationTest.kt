package com.bunch.social

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bunch.domain.BunchReportReason
import com.bunch.bundle.domain.BundleEventType
import com.bunch.bundle.dto.BundleChangedEvent
import com.bunch.bundle.service.BundleService
import com.bunch.feed.service.FeedAccessService
import com.bunch.global.exception.ErrorCode
import com.bunch.global.image.ProfileImageStorage
import com.bunch.image.domain.ImageAsset
import com.bunch.image.domain.ImageAssetRepository
import com.bunch.social.dto.CreateSocialCommentRequest
import com.bunch.social.dto.SocialContentReportRequest
import com.bunch.social.service.SocialCommentService
import com.bunch.social.service.SocialContentReportService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
@RecordApplicationEvents
class ContentModerationIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var comments: SocialCommentService
    @Autowired
    private lateinit var bundles: BundleService
    @Autowired
    private lateinit var access: FeedAccessService
    @Autowired
    private lateinit var reports: SocialContentReportService
    @Autowired
    private lateinit var assets: ImageAssetRepository
    @Autowired
    private lateinit var events: ApplicationEvents
    @MockitoBean
    private lateinit var storage: ProfileImageStorage

    private val assetIds = mutableListOf<Long>()

    @AfterEach
    fun cleanupModerationData() {
        jdbc.update("DELETE FROM push_jobs")
        jdbc.update("DELETE FROM feed_content_hides")
        jdbc.update("DELETE FROM bundles")
        assetIds.forEach { jdbc.update("DELETE FROM image_assets WHERE id=?", it) }
    }

    @Test
    fun `moderated comment erases text but retains its placeholder replies and counts for everyone`() {
        val owner = member()
        val bundle = published(publicBunch(owner), owner)
        val author = member()
        publicBunch(author)
        val root = comments.create(author, bundle, UUID.randomUUID(), CreateSocialCommentRequest("검토 대상"))
        val reporter = member()
        publicBunch(reporter)
        val reply = comments.create(reporter, bundle, UUID.randomUUID(), CreateSocialCommentRequest("답글", root.id))
        reports.comment(reporter, bundle, root.id, SocialContentReportRequest(BunchReportReason.SPAM))
        val snapshotJob = UUID.randomUUID()
        jdbc.update("""INSERT INTO push_jobs
            (id,recipient_member_id,session_id,bunch_id,dedupe_key,type,payload,available_at,expires_at,created_at)
            VALUES (?,?,?,?,?,'SOCIAL_COMMENT_CREATED',
                jsonb_build_object('aps',jsonb_build_object('alert',jsonb_build_object('body','검토 대상')),'target',jsonb_build_object('commentId',?::bigint)),
                now(),now()+interval '1 hour',now())""", snapshotJob, owner, UUID.randomUUID(),
            jdbc.queryForObject("SELECT bunch_id FROM bundles WHERE id=?", Long::class.java, bundle),
            "moderation-$snapshotJob", root.id)
        val notificationsBefore = count("notifications")
        val jobsBefore = count("push_jobs")

        comments.deleteForModeration(root.id)
        comments.deleteForModeration(root.id)

        listOf(owner, reporter, member()).forEach { viewer ->
            val page = comments.list(viewer, bundle)
            assertThat(page.totalCount).isEqualTo(2)
            val item = page.items.single()
            assertThat(item.content).isEqualTo("삭제된 댓글입니다")
            assertThat(item.deleted).isTrue()
            assertThat(item.author.memberId).isNull()
            assertThat(item.badge).isNull()
            assertThat(item.replyCount).isEqualTo(1)
            assertThat(comments.list(viewer, bundle, root.id).items.map { it.id }).containsExactly(reply.id)
        }
        assertThat(jdbc.queryForObject("SELECT content FROM social_comments WHERE id=?", String::class.java, root.id))
            .isEmpty()
        assertThat(count("notifications")).isEqualTo(notificationsBefore)
        assertThat(count("push_jobs")).isEqualTo(jobsBefore)
        assertThat(jdbc.queryForList("SELECT body FROM notifications WHERE target ->> 'commentId' = ?", String::class.java, root.id.toString()))
            .isNotEmpty().allMatch { it == "삭제된 댓글입니다" }
        assertThat(jdbc.queryForObject("SELECT payload #>> '{aps,alert,body}' FROM push_jobs WHERE id=?", String::class.java, snapshotJob))
            .isEqualTo("삭제된 댓글입니다")
    }

    @Test
    fun `moderated bundle uses ordinary deletion cascades reminders events and image cleanup without result notification`() {
        val owner = member()
        val bunch = publicBunch(owner)
        val bundle = published(bunch, owner)
        val root = comments.create(owner, bundle, UUID.randomUUID(), CreateSocialCommentRequest("원문"))
        val reporter = member()
        publicBunch(reporter)
        val report = reports.bundle(reporter, bundle, SocialContentReportRequest(BunchReportReason.SPAM))
        val asset = assets.saveAndFlush(ImageAsset(
            publicId = "moderation/${UUID.randomUUID()}",
            url = "https://example.com/photo.jpg"
        ))
        assetIds += asset.id
        jdbc.update("""INSERT INTO bundle_photos(bundle_id,image_asset_id,position,added_by,added_at)
            VALUES (?,?,0,?,now())""", bundle, asset.id, owner)
        val reminder = UUID.randomUUID()
        jdbc.update("""INSERT INTO push_jobs
            (id,recipient_member_id,session_id,bunch_id,dedupe_key,type,payload,available_at,expires_at,created_at)
            VALUES (?,?,?,?,?,'COMMENT_REMINDER',jsonb_build_object('target',jsonb_build_object('bundleId',?::bigint)),
                now(),now()+interval '1 hour',now())""", reminder, owner, UUID.randomUUID(), bunch,
            "moderation-$reminder", bundle)
        val notificationsBefore = count("notifications")
        val jobsBefore = count("push_jobs")

        bundles.deleteForModeration(bundle, "moderated-delete")

        assertError(ErrorCode.RESOURCE_NOT_FOUND) { access.readPublishedBundle(reporter, bundle) }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM social_comments WHERE id=?", Long::class.java, root.id)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bundle_photos WHERE bundle_id=?", Long::class.java, bundle)).isZero()
        assertThat(assets.findById(asset.id)).isEmpty()
        verify(storage).delete(asset.publicId)
        assertThat(jdbc.queryForObject("SELECT status FROM push_jobs WHERE id=?", String::class.java, reminder)).isEqualTo("CANCELLED")
        assertThat(jdbc.queryForObject("SELECT last_error_code FROM push_jobs WHERE id=?", String::class.java, reminder))
            .isEqualTo("BUNDLE_CLOSED")
        assertThat(events.stream(BundleChangedEvent::class.java).filter { it.bundleId == bundle }.map { it.type }.toList())
            .containsExactly(BundleEventType.DELETED)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM social_content_reports WHERE id=?", Long::class.java, report.reportId))
            .isEqualTo(1)
        assertThat(count("notifications")).isEqualTo(notificationsBefore)
        assertThat(count("push_jobs")).isEqualTo(jobsBefore)
    }

    @Test
    fun `moderation cannot delete drafts or invalid targets`() {
        val owner = member()
        val bundle = published(publicBunch(owner), owner)
        val root = comments.create(owner, bundle, UUID.randomUUID(), CreateSocialCommentRequest("원문"))
        jdbc.update("UPDATE bundles SET status='DRAFT', published_at=NULL WHERE id=?", bundle)
        assertError(ErrorCode.BUNDLE_STATE_CONFLICT) { comments.deleteForModeration(root.id) }
        assertError(ErrorCode.BUNDLE_STATE_CONFLICT) { bundles.deleteForModeration(bundle, "draft") }
        assertError(ErrorCode.INVALID_INPUT) { comments.deleteForModeration(0) }
        assertError(ErrorCode.INVALID_INPUT) { bundles.deleteForModeration(0, "invalid") }
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { comments.deleteForModeration(Long.MAX_VALUE) }
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { bundles.deleteForModeration(Long.MAX_VALUE, "missing") }
    }

    private fun publicBunch(owner: Long) = create(owner).id.also {
        jdbc.update("UPDATE bunches SET visibility='PUBLIC' WHERE id=?", it)
    }

    private fun published(bunch: Long, owner: Long): Long = jdbc.queryForObject("""INSERT INTO bundles
        (bunch_id,created_by,status,title,visibility,published_at,created_at,updated_at)
        VALUES (?,?,'PUBLISHED','moderation','PUBLIC',now(),now(),now()) RETURNING id""",
        Long::class.java, bunch, owner)!!.also {
        jdbc.update("INSERT INTO bundle_participants(bundle_id,member_id,joined_at) VALUES (?,?,now())", it, owner)
    }
}
