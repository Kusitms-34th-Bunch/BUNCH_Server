package com.bunch.notification.service

import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bundle.domain.BundleParticipantRepository
import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.domain.BundleParticipant
import com.bunch.bunch.domain.MembershipRole
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

    // BundleService holds the bunch/bundle locks and invokes this only after a real photo-add mutation.
    @Transactional(propagation = Propagation.MANDATORY)
    fun draftActivity(actorId: Long, bundle: Bundle, mutationKey: String, photoCount: Int) {
        if (photoCount <= 0 || mutationKey.isBlank() || mutationKey.length > 150) {
            throw BusinessException(ErrorCode.INVALID_INPUT)
        }
        val bunchId = bundle.bunchId
        val bundleId = bundle.id
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

    @Transactional(propagation = Propagation.MANDATORY)
    fun participantAdded(bundle: Bundle, participant: BundleParticipant, actorId: Long) {
        bundleNotification(
            bundle, NotificationType.DRAFT_PARTICIPANT_ADDED, listOf(participant.memberId), actorId,
            " 번들에 함께하게 됐어요", "코멘트를 남기고 번들 완성을 함께해 주세요.",
            NotificationTargetKind.DRAFT, "participation:${participant.id}"
        )
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun participantRemoved(bundle: Bundle, memberId: Long, actorId: Long) {
        bundleNotification(
            bundle, NotificationType.DRAFT_PARTICIPANT_REMOVED, listOf(memberId), actorId,
            " 번들 참여 멤버에서 빠졌어요", "추가한 사진은 번들에 그대로 남아요.",
            NotificationTargetKind.DRAFT_LIST
        )
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun participantsInsufficient(bundle: Bundle, actorId: Long) {
        bundleNotification(
            bundle, NotificationType.DRAFT_PARTICIPANTS_INSUFFICIENT, listOf(bundle.createdBy), actorId,
            "에 참여 멤버가 부족해요", "함께할 멤버를 추가해 주세요.", NotificationTargetKind.DRAFT
        )
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun readyToPublish(bundle: Bundle, actorId: Long) {
        val people = participants.findAllByBundleIdOrderByJoinedAtAscIdAsc(bundle.id).map { it.memberId }.toSet()
        val recipients = memberships.findActiveMembers(bundle.bunchId).filter {
            it.member.id == bundle.createdBy || (it.member.id in people && it.role != MembershipRole.MEMBER)
        }.map { it.member.id }
        bundleNotification(
            bundle, NotificationType.DRAFT_READY_TO_PUBLISH, recipients, actorId,
            " 번들을 이제 발행할 수 있어요", "함께한 순간을 하나의 번들로 완성해 보세요.", NotificationTargetKind.DRAFT,
            "first-ready:${bundle.id}"
        )
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun published(bundle: Bundle, actorId: Long, grew: Boolean) {
        val recipients = memberships.findActiveMembers(bundle.bunchId).map { it.member.id }
        bundleNotification(
            bundle, NotificationType.BUNDLE_PUBLISHED, recipients, actorId,
            " 번들이 완성됐어요", "함께한 순간들이 하나의 소중한 기록으로 남았어요.", NotificationTargetKind.BUNDLE
        )
        if (grew) {
            notifications.saveAll(recipients.filter { it != actorId }.map {
                Notification(
                    recipientMemberId = it,
                    bunchId = bundle.bunchId,
                    type = NotificationType.CHARACTER_GROWN,
                    dedupeKey = "CHARACTER_GROWN:${bundle.id}:${bundle.revision}",
                    title = "우리 번치의 뭉치가 성장했어요!",
                    body = "차곡차곡 쌓인 추억이 뭉치를 키웠어요.",
                    target = NotificationTarget(NotificationTargetKind.BUNCH_HOME, bundle.bunchId),
                    createdAt = clock.instant()
                )
            })
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun commentReminder(bundle: Bundle, participant: BundleParticipant, hours: Int, now: Instant) {
        bundleNotification(
            bundle, NotificationType.COMMENT_REMINDER, listOf(participant.memberId), null,
            " 번들 코멘트를 기다리고 있어요!", "코멘트를 남기고 번들 완성을 함께해 주세요.",
            NotificationTargetKind.DRAFT, "participation:${participant.id}:$hours", now
        )
    }

    private fun bundleNotification(
        bundle: Bundle,
        type: NotificationType,
        recipients: List<Long>,
        actorId: Long?,
        suffix: String,
        body: String,
        kind: NotificationTargetKind,
        occurrence: String = "${bundle.id}:${bundle.revision}",
        now: Instant = clock.instant()
    ) {
        val current = memberships.findActiveMembers(bundle.bunchId).map { it.member.id }.toSet()
        notifications.saveAll(recipients.distinct().filter { it != actorId && it in current }.sorted().map {
            Notification(
                recipientMemberId = it,
                bunchId = bundle.bunchId,
                type = type,
                dedupeKey = "$type:$occurrence",
                title = "'${bundle.title.ifBlank { "제목 미정" }}'$suffix",
                body = body,
                target = NotificationTarget(
                    kind,
                    bundle.bunchId,
                    bundle.id.takeUnless { kind == NotificationTargetKind.DRAFT_LIST }
                ),
                createdAt = now
            )
        })
    }
}
