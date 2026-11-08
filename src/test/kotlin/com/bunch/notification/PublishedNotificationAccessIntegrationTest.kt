package com.bunch.notification

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bunch.service.BunchAccessNoticeService
import com.bunch.bunch.domain.Visibility
import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.domain.BundleStatus
import com.bunch.feed.service.FeedAccessService
import com.bunch.global.exception.ErrorCode
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
import java.sql.Timestamp
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

    @Autowired
    private lateinit var feedAccess: FeedAccessService

    @Autowired
    private lateinit var accessNotices: BunchAccessNoticeService

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
    fun `owner removal notice prevents opening an unread reply and commits its read timestamp`() {
        val notification = removedRecipientReply()
        assertThat(storedReadAt(notification.id)).isNull()
        assertError(ErrorCode.BUNCH_ACCESS_UNAVAILABLE) {
            feedAccess.readPublishedBundle(notification.recipientMemberId, notification.target.bundleId!!)
        }

        val opened = service.open(notification.recipientMemberId, notification.bunchId, notification.id)

        assertThat(opened.available).isFalse()
        assertThat(opened.target).isNull()
        assertThat(opened.readAt).isNotNull()
        assertThat(storedReadAt(notification.id)?.toInstant()).isEqualTo(opened.readAt)
        assertThat(service.open(notification.recipientMemberId, notification.bunchId, notification.id).readAt)
            .isEqualTo(opened.readAt)
        assertThat(publishedAccess.canDeliver(notification.bunchId, notification.target)).isTrue()
    }

    @Test
    fun `expired owner notice allows the external reply to open`() {
        val notification = removedRecipientReply()
        jdbc.update(
            "UPDATE bunch_access_notices SET occurred_at = now() - interval '31 days', " +
                "expires_at = now() - interval '1 day' WHERE recipient_member_id = ?",
            notification.recipientMemberId
        )

        val opened = service.open(notification.recipientMemberId, notification.bunchId, notification.id)
        assertThat(opened.available).isTrue()
    }

    @Test
    fun `acknowledged owner notice allows the external reply to open`() {
        val notification = removedRecipientReply()
        val noticeId = jdbc.queryForObject(
            "SELECT id FROM bunch_access_notices WHERE recipient_member_id = ?",
            Long::class.java,
            notification.recipientMemberId
        )!!
        accessNotices.delete(notification.recipientMemberId, noticeId)

        val opened = service.open(notification.recipientMemberId, notification.bunchId, notification.id)
        assertThat(opened.available).isTrue()
    }

    @Test
    fun `active rejoin ignores an old owner notice`() {
        val notification = removedRecipientReply()
        jdbc.update(
            "UPDATE bunch_memberships SET status = 'ACTIVE', ended_at = NULL WHERE member_id = ? AND bunch_id = ?",
            notification.recipientMemberId,
            notification.target.targetBunchId
        )

        val opened = service.open(notification.recipientMemberId, notification.bunchId, notification.id)
        assertThat(opened.available).isTrue()
        assertThat(count("bunch_access_notices")).isEqualTo(1)
    }

    @Test
    fun `another recipients owner notice does not prevent opening`() {
        val removed = removedRecipientReply()
        val recipient = member()
        val inbox = create(recipient).id
        val ownerBunch = removed.target.targetBunchId!!
        val notification = save(recipient, inbox, removed.target.bundleId!!, ownerBunch, ownerBunch)

        assertThat(service.open(recipient, inbox, notification.id).available).isTrue()
    }

    private fun removedRecipientReply(): Notification {
        val recipient = member()
        val owner = member()
        val ownerBunch = create(owner).id
        join(recipient, issue(owner, ownerBunch))
        val inbox = create(recipient).id
        jdbc.update("UPDATE bunches SET visibility = 'PUBLIC' WHERE id = ?", ownerBunch)
        val bundle = published(owner, ownerBunch)
        val notification = save(recipient, inbox, bundle, ownerBunch, ownerBunch)
        jdbc.update(
            """INSERT INTO bunch_access_notices
                (recipient_member_id, bunch_id, membership_id, bunch_name, character_type,
                    reason, occurred_at, expires_at)
                SELECT membership.member_id, bunch.id, membership.id, bunch.name, bunch.character_type,
                    'REMOVED', now(), now() + interval '30 days'
                FROM bunch_memberships membership JOIN bunches bunch ON bunch.id = membership.bunch_id
                WHERE membership.member_id = ? AND bunch.id = ?""",
            recipient,
            ownerBunch
        )
        jdbc.update(
            "UPDATE bunch_memberships SET status = 'REMOVED', ended_at = now() WHERE member_id = ? AND bunch_id = ?",
            recipient,
            ownerBunch
        )
        return notification
    }

    private fun storedReadAt(notificationId: Long): Timestamp? = jdbc.queryForObject(
        "SELECT read_at FROM notifications WHERE id = ?",
        Timestamp::class.java,
        notificationId
    )

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
