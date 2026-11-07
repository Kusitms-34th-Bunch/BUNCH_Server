package com.bunch.feed

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bunch.domain.Visibility
import com.bunch.bunch.service.BunchSocialService
import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.domain.BundleParticipant
import com.bunch.bundle.domain.BundleParticipantRepository
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.domain.BundleStatus
import com.bunch.feed.domain.FeedContentHideRepository
import com.bunch.feed.domain.FeedContentKind
import com.bunch.feed.service.FeedAccessService
import com.bunch.notification.domain.NotificationRepository
import com.bunch.notification.domain.NotificationType
import com.bunch.notification.service.NotificationService
import com.bunch.notification.service.PublishedNotificationAccess
import com.bunch.social.dto.CreateSocialCommentRequest
import com.bunch.social.service.SocialCommentService
import com.bunch.social.service.SocialLikeService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class FeedSocialNotificationIntegrationTest : BunchIntegrationSupport() {
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
    private lateinit var hides: FeedContentHideRepository

    @Autowired
    private lateinit var transaction: TransactionTemplate

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
    fun `actual external reply retains inbox context and reporter hide applies only to that bunch`() {
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

        transaction.executeWithoutResult {
            hides.hideForReporter(FeedContentKind.BUNDLE, bundle, actorBunch)
        }

        assertThat(notificationService.open(actor, actorBunch, event.id).available).isFalse()
        assertThat(publishedAccess.canDeliver(actorBunch, event.target)).isFalse()
        val other = member()
        assertThat(feedAccess.readPublishedBundle(other, bundle).id).isEqualTo(bundle)
        assertThat(comments.counts(other, listOf(bundle))[bundle]).isEqualTo(2)
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
