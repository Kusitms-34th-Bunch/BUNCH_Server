package com.bunch.notification

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.domain.BundleStatus
import com.bunch.bunch.domain.Visibility
import com.bunch.notification.domain.Notification
import com.bunch.notification.domain.NotificationRepository
import com.bunch.notification.domain.NotificationTargetKind
import com.bunch.notification.domain.NotificationType
import com.bunch.notification.dto.NotificationTarget
import com.bunch.notification.service.NotificationService
import com.bunch.notification.service.PublishedNotificationAccess
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
class PublishedNotificationAccessIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var notifications: NotificationRepository

    @Autowired
    private lateinit var service: NotificationService

    @Autowired
    private lateinit var publishedAccess: PublishedNotificationAccess

    @Autowired
    private lateinit var bundles: BundleRepository

    @AfterEach
    fun clearHides() {
        jdbc.update("DELETE FROM feed_content_hides")
    }

    @Test
    fun `reply opens an external bundle using inbox and owner as separate contexts`() {
        val recipient = member()
        val inbox = create(recipient).id
        val owner = member()
        val ownerBunch = create(owner).id
        jdbc.update("UPDATE bunches SET visibility = 'PUBLIC' WHERE id = ?", ownerBunch)
        val bundle = published(owner, ownerBunch)
        val notification = save(recipient, inbox, bundle, ownerBunch, ownerBunch)

        assertThat(service.open(recipient, inbox, notification.id).available).isTrue()
        jdbc.update("UPDATE bundles SET visibility = 'PRIVATE' WHERE id = ?", bundle)
        val unavailable = service.open(recipient, inbox, notification.id)
        assertThat(unavailable.available).isFalse()
        assertThat(unavailable.target).isNull()
        assertThat(unavailable.readAt).isNotNull()
    }

    @Test
    fun `late block of reaction source hides notification without losing read state`() {
        val recipient = member()
        val inbox = create(recipient).id
        val sourceOwner = member()
        val source = create(sourceOwner).id
        val bundle = published(recipient, inbox)
        val notification = save(recipient, inbox, bundle, inbox, source)
        assertThat(service.open(recipient, inbox, notification.id).available).isTrue()

        jdbc.update(
            "INSERT INTO bunch_blocks(blocker_bunch_id, blocked_bunch_id, created_by_member_id, created_at) " +
                "VALUES (?, ?, ?, now())",
            source, inbox, sourceOwner
        )
        val opened = service.open(recipient, inbox, notification.id)
        assertThat(opened.available).isFalse()
        assertThat(opened.readAt).isNotNull()
    }

    @Test
    fun `global and reporter hides are checked again before opening and delivery`() {
        val recipient = member()
        val inbox = create(recipient).id
        val bundle = published(recipient, inbox)
        val notification = save(recipient, inbox, bundle, inbox, inbox)
        jdbc.update(
            "INSERT INTO feed_content_hides(target_kind, target_id, reporter_bunch_id, reason) " +
                "VALUES ('BUNDLE', ?, ?, 'REPORTED')",
            bundle, inbox
        )
        assertThat(service.open(recipient, inbox, notification.id).available).isFalse()
        assertThat(publishedAccess.canDeliver(inbox, notification.target)).isFalse()
        jdbc.update("DELETE FROM feed_content_hides")
        assertThat(publishedAccess.canDeliver(inbox, notification.target)).isTrue()
        jdbc.update(
            "INSERT INTO feed_content_hides(target_kind, target_id, reason) VALUES ('BUNDLE', ?, 'review')",
            bundle
        )
        assertThat(service.open(recipient, inbox, notification.id).available).isFalse()
    }

    @Test
    fun `delivery keeps inbox context while opening follows server current bunch`() {
        val recipient = member()
        val inbox = create(recipient).id
        val bundle = published(recipient, inbox, Visibility.PRIVATE)
        val notification = save(recipient, inbox, bundle, inbox, inbox)
        create(recipient)

        assertThat(publishedAccess.canDeliver(inbox, notification.target)).isTrue()
        assertThat(service.open(recipient, inbox, notification.id).available).isFalse()
    }

    @Test
    fun `former inbox member can explicitly open public content with zero memberships`() {
        val recipient = member()
        val inbox = create(recipient).id
        jdbc.update("UPDATE bunches SET visibility = 'PUBLIC' WHERE id = ?", inbox)
        val bundle = published(recipient, inbox)
        val notification = save(recipient, inbox, bundle, inbox, inbox)
        jdbc.update(
            "UPDATE bunch_memberships SET status = 'LEFT', ended_at = joined_at + interval '1 second' WHERE member_id = ?",
            recipient
        )

        val opened = service.open(recipient, inbox, notification.id)
        assertThat(opened.available).isTrue()
        assertThat(opened.readAt).isNotNull()
        jdbc.update("UPDATE bundles SET visibility = 'PRIVATE' WHERE id = ?", bundle)
        assertThat(service.open(recipient, inbox, notification.id).available).isFalse()
    }

    private fun published(owner: Long, bunchId: Long, visibility: Visibility = Visibility.PUBLIC): Long {
        val now = Instant.now()
        return bundles.saveAndFlush(
            Bundle(
                bunchId = bunchId,
                createdBy = owner,
                status = BundleStatus.PUBLISHED,
                visibility = visibility,
                title = "알림 대상",
                createdAt = now,
                updatedAt = now,
                publishedAt = now
            )
        ).id
    }

    private fun save(recipient: Long, inbox: Long, bundle: Long, owner: Long, source: Long): Notification =
        notifications.saveAndFlush(
            Notification(
                recipientMemberId = recipient,
                bunchId = inbox,
                type = NotificationType.SOCIAL_COMMENT_CREATED,
                dedupeKey = UUID.randomUUID().toString(),
                title = "새 답글",
                body = "내용",
                target = NotificationTarget(
                    kind = NotificationTargetKind.BUNDLE_COMMENTS,
                    bunchId = inbox,
                    bundleId = bundle,
                    targetBunchId = owner,
                    sourceBunchId = source
                ),
                createdAt = Instant.now()
            )
        )
}
