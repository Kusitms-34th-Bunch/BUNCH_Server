package com.bunch.feed

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bunch.domain.Visibility
import com.bunch.bunch.domain.BunchReportReason
import com.bunch.bunch.dto.BunchReportRequest
import com.bunch.bunch.service.BunchSocialService
import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.domain.BundleParticipant
import com.bunch.bundle.domain.BundleParticipantRepository
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.domain.BundleStatus
import com.bunch.feed.service.FeedAccessService
import com.bunch.notification.domain.NotificationRepository
import com.bunch.notification.domain.NotificationType
import com.bunch.notification.service.NotificationService
import com.bunch.notification.service.PublishedNotificationAccess
import com.bunch.social.dto.CreateSocialCommentRequest
import com.bunch.social.dto.SocialContentReportRequest
import com.bunch.social.service.SocialContentReportService
import com.bunch.social.service.SocialCommentService
import com.bunch.social.service.SocialLikeService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class FeedSocialNotificationIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var reports: SocialContentReportService

    @Autowired
    private lateinit var likes: SocialLikeService

    @Autowired
    private lateinit var comments: SocialCommentService

    @Autowired
    private lateinit var relations: BunchSocialService

    @Autowired
    private lateinit var notifications: NotificationRepository

    @Autowired
    private lateinit var notificationService: NotificationService

    @Autowired
    private lateinit var publishedAccess: PublishedNotificationAccess

    @Autowired
    private lateinit var feedAccess: FeedAccessService

    @Autowired
    private lateinit var bundles: BundleRepository

    @Autowired
    private lateinit var participants: BundleParticipantRepository

    @AfterEach
    fun clearGlobalHides() {
        jdbc.update("DELETE FROM feed_content_hides")
    }

    @Test
    fun `actual reactions and notifications share block filtering and recover after unblock`() {
        val owner = member()
        val ownerBunch = publicBunch(owner)
        val bundle = published(owner, ownerBunch)
        participants.saveAndFlush(BundleParticipant(bundleId = bundle, memberId = owner))
        val actor = member()
        val actorBunch = publicBunch(actor)
        likes.like(actor, bundle)
        comments.create(actor, bundle, UUID.randomUUID(), CreateSocialCommentRequest("함께 남긴 댓글"))
        val events = notifications.findAll().filter {
            it.type in setOf(NotificationType.BUNDLE_LIKED, NotificationType.SOCIAL_COMMENT_CREATED)
        }
        assertThat(events).hasSize(2)
        events.forEach {
            assertThat(it.target.sourceBunchId).isEqualTo(actorBunch)
            assertThat(notificationService.open(owner, ownerBunch, it.id).available).isTrue()
        }
        assertThat(likes.summary(owner, bundle).likeCount).isEqualTo(1)
        assertThat(comments.counts(owner, listOf(bundle))[bundle]).isEqualTo(1)

        relations.block(owner, ownerBunch, actorBunch)

        assertThat(likes.summary(owner, bundle).likeCount).isZero()
        assertThat(comments.counts(owner, listOf(bundle))[bundle]).isZero()
        events.forEach {
            val result = notificationService.open(owner, ownerBunch, it.id)
            assertThat(result.available).isFalse()
            assertThat(result.readAt).isNotNull()
            assertThat(publishedAccess.canDeliver(ownerBunch, it.target)).isFalse()
        }
        assertThat(count("social_bundle_likes")).isEqualTo(1)
        assertThat(count("social_comments")).isEqualTo(1)

        val block = relations.blocks(owner, ownerBunch, 0, 20).items.single()
        relations.unblock(owner, ownerBunch, actorBunch, block.id)
        assertThat(likes.summary(owner, bundle).likeCount).isEqualTo(1)
        assertThat(comments.counts(owner, listOf(bundle))[bundle]).isEqualTo(1)
    }

    @Test
    fun `actual external reply retains inbox access and delivery after all reports and legacy reporter hides`() {
        val owner = member()
        val ownerBunch = publicBunch(owner)
        val bundle = published(owner, ownerBunch)
        participants.saveAndFlush(BundleParticipant(bundleId = bundle, memberId = owner))
        val actor = member()
        val actorBunch = publicBunch(actor)
        val root = comments.create(actor, bundle, UUID.randomUUID(), CreateSocialCommentRequest("원댓글"))
        val reply = comments.create(owner, bundle, UUID.randomUUID(), CreateSocialCommentRequest("답글", root.id))
        val event = notifications.findAll().single {
            it.dedupeKey == "SOCIAL_COMMENT_CREATED:${reply.id}" && it.recipientMemberId == actor
        }
        assertThat(event.bunchId).isEqualTo(actorBunch)
        assertThat(event.target.targetBunchId).isEqualTo(ownerBunch)
        assertThat(event.target.sourceBunchId).isEqualTo(ownerBunch)
        assertThat(notificationService.open(actor, actorBunch, event.id).available).isTrue()

        val beforeNotifications = count("notifications")
        val beforePushJobs = count("push_jobs")
        relations.report(actor, actorBunch, BunchReportRequest(ownerBunch, BunchReportReason.SPAM))
        reports.bundle(actor, bundle, SocialContentReportRequest(BunchReportReason.SPAM))
        reports.comment(actor, bundle, reply.id, SocialContentReportRequest(BunchReportReason.SPAM))
        jdbc.update("INSERT INTO feed_content_hides(target_kind,target_id,reporter_bunch_id,reason) " +
            "VALUES ('BUNDLE',?,?,'LEGACY')", bundle, actorBunch)
        jdbc.update("INSERT INTO feed_content_hides(target_kind,target_id,reporter_bunch_id,reason) " +
            "VALUES ('COMMENT',?,?,'LEGACY')", reply.id, actorBunch)

        assertThat(notificationService.open(actor, actorBunch, event.id).available).isTrue()
        assertThat(publishedAccess.canDeliver(actorBunch, event.target)).isTrue()
        assertThat(comments.counts(actor, listOf(bundle))[bundle]).isEqualTo(2)
        assertThat(count("notifications")).isEqualTo(beforeNotifications)
        assertThat(count("push_jobs")).isEqualTo(beforePushJobs)
        val nextReply = comments.create(owner, bundle, UUID.randomUUID(), CreateSocialCommentRequest("새 답글", root.id))
        val nextEvent = notifications.findAll().single {
            it.dedupeKey == "SOCIAL_COMMENT_CREATED:${nextReply.id}" && it.recipientMemberId == actor
        }
        assertThat(notificationService.open(actor, actorBunch, nextEvent.id).available).isTrue()
        assertThat(publishedAccess.canDeliver(actorBunch, nextEvent.target)).isTrue()
        val other = member()
        assertThat(feedAccess.readPublishedBundle(other, bundle).id).isEqualTo(bundle)
        assertThat(comments.counts(other, listOf(bundle))[bundle]).isEqualTo(3)
    }

    private fun publicBunch(owner: Long): Long = create(owner).id.also {
        jdbc.update("UPDATE bunches SET visibility = 'PUBLIC' WHERE id = ?", it)
    }

    private fun published(owner: Long, bunchId: Long): Long {
        val now = Instant.now()
        return bundles.saveAndFlush(
            Bundle(
                bunchId = bunchId,
                createdBy = owner,
                status = BundleStatus.PUBLISHED,
                title = "통합 검증 번들",
                visibility = Visibility.PUBLIC,
                createdAt = now,
                updatedAt = now,
                publishedAt = now
            )
        ).id
    }
}
