package com.bunch.notification.service

import com.bunch.notification.domain.Notification
import com.bunch.notification.domain.NotificationType
import com.bunch.notification.domain.PushAuthorizationStatus
import com.bunch.notification.domain.PushDeviceRepository
import com.bunch.notification.domain.PushJob
import com.bunch.notification.domain.PushJobRepository
import com.bunch.notification.domain.PushJobStatus
import com.bunch.notification.domain.PushPreferenceRepository
import com.bunch.notification.dto.NotificationTarget
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.collections.Collection

@Service
class PushJobWriter(
    private val devices: PushDeviceRepository,
    private val preferences: PushPreferenceRepository,
    private val jobs: PushJobRepository,
    private val payloads: PushPayloadFactory
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun enqueue(notifications: Collection<Notification>, now: Instant) {
        notifications.forEach { notification ->
            if (notification.id <= 0) throw IllegalArgumentException("Notification must be persisted")
            enqueueNotification(notification)
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun refreshMergedActivity(notification: Notification) {
        val groupUntil = notification.groupUntil ?: return
        jobs.findAllByNotificationIdAndStatus(notification.id, PushJobStatus.PENDING).forEach { job ->
            job.payload = payloads.forNotification(job.id, notification)
            job.availableAt = groupUntil
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun enqueueDetached(
        recipientMemberIds: List<Long>,
        bunchId: Long,
        type: NotificationType,
        dedupeKey: String,
        title: String,
        body: String,
        target: NotificationTarget,
        now: Instant
    ) {
        recipientMemberIds.distinct().forEach { memberId ->
            enqueueSnapshot(
                memberId = memberId,
                notificationId = null,
                bunchId = bunchId,
                type = type,
                dedupeKey = dedupeKey,
                title = title,
                body = body,
                target = target,
                createdAt = now,
                availableAt = now,
                expiresAt = now.plus(PUSH_TTL)
            )
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun cancelCommentReminderForMember(bundleId: Long, memberId: Long) {
        jobs.cancelPendingCommentReminderForMember(bundleId, memberId, COMMENT_COMPLETED_OR_LEFT)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun cancelCommentRemindersForBundle(bundleId: Long) {
        jobs.cancelPendingCommentReminders(bundleId, BUNDLE_CLOSED)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun cancelCommentRemindersForBunchLeave(bunchId: Long, memberId: Long) {
        jobs.cancelPendingCommentRemindersForBunchLeave(memberId, bunchId, MEMBERSHIP_ENDED)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun cancelPendingForSession(memberId: Long, sessionId: UUID) {
        jobs.cancelPendingForSession(memberId, sessionId, SESSION_ENDED)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun cancelPendingForDevice(memberId: Long, deviceId: Long) {
        jobs.cancelPendingForDevice(memberId, deviceId, DEVICE_UNREGISTERED)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun cancelPendingRemovalNoticeForRejoin(memberId: Long, bunchId: Long) {
        jobs.cancelPendingRemovalNoticeForRejoin(memberId, bunchId, REJOINED)
    }

    private fun enqueueNotification(notification: Notification) {
        enqueueSnapshot(
            memberId = notification.recipientMemberId,
            notificationId = notification.id,
            bunchId = notification.bunchId,
            type = notification.type,
            dedupeKey = notification.dedupeKey,
            title = notification.title,
            body = notification.body,
            target = notification.target,
            createdAt = notification.createdAt,
            availableAt = notification.groupUntil ?: notification.createdAt,
            expiresAt = notification.createdAt.plus(PUSH_TTL),
            notification = notification
        )
    }

    private fun enqueueSnapshot(
        memberId: Long,
        notificationId: Long?,
        bunchId: Long,
        type: NotificationType,
        dedupeKey: String,
        title: String,
        body: String,
        target: NotificationTarget,
        createdAt: Instant,
        availableAt: Instant,
        expiresAt: Instant,
        notification: Notification? = null
    ) {
        if (!isPushEnabled(memberId, type)) return
        devices.findAllByMemberId(memberId)
            .filter { it.authorizationStatus.canReceive }
            .filterNot { jobs.existsByRecipientMemberIdAndDeviceIdAndDedupeKey(memberId, it.id, dedupeKey) }
            .forEach { device ->
                val id = UUID.randomUUID()
                jobs.save(
                    PushJob(
                        id = id,
                        deviceId = device.id,
                        recipientMemberId = memberId,
                        sessionId = device.sessionId,
                        bunchId = bunchId,
                        notificationId = notificationId,
                        dedupeKey = dedupeKey,
                        type = type,
                        payload = notification?.let { payloads.forNotification(id, it) }
                            ?: payloads.forDetached(id, type, title, body, target),
                        availableAt = availableAt,
                        expiresAt = expiresAt,
                        createdAt = createdAt
                    )
                )
            }
    }

    private fun isPushEnabled(memberId: Long, type: NotificationType): Boolean {
        val preference = preferences.findById(memberId).orElse(null) ?: return true
        return preference.enabled && preference.types[type.name] != false
    }

    private companion object {
        val PUSH_TTL: Duration = Duration.ofHours(24)
        const val COMMENT_COMPLETED_OR_LEFT = "COMMENT_COMPLETED_OR_LEFT"
        const val BUNDLE_CLOSED = "BUNDLE_CLOSED"
        const val MEMBERSHIP_ENDED = "BUNCH_MEMBERSHIP_ENDED"
        const val SESSION_ENDED = "SESSION_ENDED"
        const val DEVICE_UNREGISTERED = "DEVICE_UNREGISTERED"
        const val REJOINED = "REJOINED"
        val PushAuthorizationStatus.canReceive: Boolean
            get() = this == PushAuthorizationStatus.AUTHORIZED ||
                this == PushAuthorizationStatus.PROVISIONAL
    }
}
