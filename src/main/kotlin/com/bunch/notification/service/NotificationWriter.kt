package com.bunch.notification.service

import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bundle.domain.BundleParticipantRepository
import com.bunch.bundle.service.BundleAccessService
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.notification.domain.Notification
import com.bunch.notification.domain.NotificationActivity
import com.bunch.notification.domain.NotificationRepository
import com.bunch.notification.domain.NotificationTargetKind
import com.bunch.notification.domain.NotificationType
import com.bunch.notification.dto.NotificationTarget
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Service
class NotificationWriter(
    private val notifications: NotificationRepository,
    private val memberships: BunchMembershipRepository,
    private val participants: BundleParticipantRepository,
    private val access: BundleAccessService,
    private val clock: Clock
) {
    // Call only after a real membership insertion, under the producer's member/bunch locks.
    @Transactional(propagation = Propagation.MANDATORY)
    fun memberJoined(bunchId: Long, membershipId: Long, actorId: Long, nickname: String, now: Instant) {
        val recipients = memberships.findActiveMembers(bunchId).filter { it.member.id != actorId }
        notifications.saveAll(recipients.map { recipient ->
            Notification(
                recipientMemberId = recipient.member.id,
                bunchId = bunchId,
                type = NotificationType.MEMBER_JOINED,
                dedupeKey = "MEMBER_JOINED:$membershipId",
                title = "새 멤버가 들어왔어요",
                body = "${nickname}님이 번치에 참여했어요.",
                target = NotificationTarget(NotificationTargetKind.MEMBERS, bunchId),
                createdAt = now
            )
        })
    }

    // Integration entry point: invoke once after the real photo-add mutation, never on request replay.
    // The bundle producer owns its successful-request record. There is no second event ledger here.
    @Transactional(propagation = Propagation.MANDATORY)
    fun draftActivity(actorId: Long, bunchId: Long, bundleId: Long, mutationKey: String, photoCount: Int) {
        if (photoCount <= 0 || mutationKey.isBlank() || mutationKey.length > 150) {
            throw BusinessException(ErrorCode.INVALID_INPUT)
        }
        val bundle = access.lock(actorId, bunchId, bundleId)
        access.requireParticipant(actorId, bundle)
        val now = clock.instant()
        val currentParticipants = participants.findAllByBundleIdOrderByJoinedAtAscIdAsc(bundleId)
            .map { it.memberId }.toSet()
        val active = memberships.findActiveMembers(bunchId)
        val actor = active.first { it.member.id == actorId }.member
        val recipients = active.filter {
            it.member.id != actorId && it.member.id in currentParticipants
        }.sortedBy { it.member.id }
        recipients.forEach { recipient ->
            val current = notifications.openActivity(recipient.member.id, bunchId, bundleId, now)
            if (current == null) {
                val activity = NotificationActivity(mapOf(actorId to actor.nickname!!), photoCount)
                notifications.save(
                    Notification(
                        recipientMemberId = recipient.member.id,
                        bunchId = bunchId,
                        type = NotificationType.DRAFT_ACTIVITY,
                        dedupeKey = "DRAFT_ACTIVITY:$bundleId:$mutationKey",
                        title = "'${bundle.title.ifBlank { "제목 미정" }}' 번들에 새 활동이 있어요",
                        body = activity.body(),
                        target = NotificationTarget(NotificationTargetKind.DRAFT, bunchId, bundleId),
                        activityData = activity,
                        groupUntil = now.plus(Duration.ofMinutes(10)),
                        createdAt = now
                    )
                )
            } else {
                val activity = requireNotNull(current.activityData).added(actorId, actor.nickname!!, photoCount)
                current.activityData = activity
                current.body = activity.body()
                current.readAt = null
            }
        }
    }
}
