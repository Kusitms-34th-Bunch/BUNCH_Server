package com.bunch.notification.service

import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bundle.domain.BundleParticipantRepository
import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.domain.BundleParticipant
import com.bunch.bunch.domain.MembershipRole
import com.bunch.bunch.domain.Bunch
import com.bunch.bunch.domain.BunchFollow
import com.bunch.bunch.domain.BunchFollowStatus
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
    private val pushJobs: PushJobWriter,
    private val memberships: BunchMembershipRepository,
    private val participants: BundleParticipantRepository,
    private val clock: Clock
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun followCreated(follower: Bunch, target: Bunch, follow: BunchFollow, actorId: Long) {
        val approved = follow.status == BunchFollowStatus.APPROVED
        socialNotification(
            recipientBunchId = target.id,
            otherBunch = follower,
            follow = follow,
            actorId = actorId,
            type = if (approved) NotificationType.BUNCH_FOLLOWED else NotificationType.FOLLOW_REQUESTED,
            title = if (approved) "${follower.name} 번치가 우리 번치를 팔로우했어요"
                else "${follower.name} 번치가 팔로우를 요청했어요",
            body = if (approved) "'${follower.name}' 번치의 기록도 구경해 보세요."
                else "요청을 확인하고 승인해 보세요."
        )
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun followApproved(follower: Bunch, target: Bunch, follow: BunchFollow, actorId: Long) {
        followCreated(follower, target, follow, actorId)
        socialNotification(
            recipientBunchId = follower.id,
            otherBunch = target,
            follow = follow,
            actorId = actorId,
            type = NotificationType.FOLLOW_REQUEST_APPROVED,
            title = "${target.name} 번치가 팔로우 요청을 승인했어요",
            body = "이제 '${target.name}' 번치의 새로운 기록을 만나볼 수 있어요."
        )
    }

    private fun socialNotification(
        recipientBunchId: Long,
        otherBunch: Bunch,
        follow: BunchFollow,
        actorId: Long,
        type: NotificationType,
        title: String,
        body: String
    ) {
        saveNotifications(memberships.findActiveMembers(recipientBunchId).filter { it.member.id != actorId }.map {
            Notification(
                recipientMemberId = it.member.id,
                bunchId = recipientBunchId,
                type = type,
                dedupeKey = "$type:${follow.id}",
                title = title,
                body = body,
                target = NotificationTarget(
                    NotificationTargetKind.OTHER_BUNCH_PROFILE,
                    recipientBunchId,
                    targetBunchId = otherBunch.id
                ),
                createdAt = clock.instant()
            )
        })
    }

    // Call only after a real membership insertion, under the producer's member/bunch locks.
    @Transactional(propagation = Propagation.MANDATORY)
    fun memberJoined(bunchId: Long, membershipId: Long, actorId: Long, nickname: String, now: Instant) {
        val recipients = memberships.findActiveMembers(bunchId).filter { it.member.id != actorId }
        saveNotifications(recipients.map { recipient ->
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

    @Transactional(propagation = Propagation.MANDATORY)
    fun ownerTransferred(
        bunchId: Long,
        membershipId: Long,
        recipientMemberId: Long,
        version: Long,
        bunchName: String,
        now: Instant
    ) {
        saveNotifications(listOf(
            Notification(
                recipientMemberId = recipientMemberId,
                bunchId = bunchId,
                type = NotificationType.OWNER_TRANSFERRED,
                dedupeKey = "OWNER_TRANSFERRED:$membershipId:$version",
                title = "$bunchName 번치의 관리자가 되었어요",
                body = "관리자 권한으로 번치를 자유롭게 관리해 보세요.",
                target = NotificationTarget(NotificationTargetKind.ROLE_PERMISSIONS, bunchId),
                createdAt = now
            )
        ))
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
                saveNotifications(listOf(
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
                ))
            } else {
                val activity = requireNotNull(current.activityData).added(actorId, actor.nickname!!, photoCount)
                current.activityData = activity
                current.body = activity.body()
                current.readAt = null
                pushJobs.refreshMergedActivity(current)
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
            saveNotifications(recipients.filter { it != actorId }.map {
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

    @Transactional(propagation = Propagation.MANDATORY)
    fun socialCommentCreated(
        bundle: Bundle,
        sourceBunch: Bunch,
        commentId: Long,
        actorId: Long,
        content: String,
        parentAuthorId: Long?,
        parentBunchId: Long?,
        now: Instant
    ) {
        val recipients = linkedMapOf<Long, Pair<Long, Boolean>>()
        if (sourceBunch.id != bundle.bunchId) {
            val participantIds = participants.findAllByBundleIdOrderByJoinedAtAscIdAsc(bundle.id)
                .map { it.memberId }.toSet()
            memberships.findActiveMembers(bundle.bunchId)
                .filter { it.member.id in participantIds && it.member.id != actorId }
                .forEach { recipients[it.member.id] = bundle.bunchId to false }
        }
        if (parentAuthorId != null && parentBunchId != null && parentAuthorId != actorId) {
            val parentStillActive = memberships.findActiveMembers(parentBunchId)
                .any { it.member.id == parentAuthorId }
            if (parentStillActive) recipients[parentAuthorId] = parentBunchId to true
        }
        saveNotifications(recipients.toSortedMap().map { (recipientId, inbox) ->
            Notification(
                recipientMemberId = recipientId,
                bunchId = inbox.first,
                type = NotificationType.SOCIAL_COMMENT_CREATED,
                dedupeKey = "SOCIAL_COMMENT_CREATED:$commentId",
                title = if (inbox.second) "내 댓글에 답글이 달렸어요"
                    else "${sourceBunch.name} 번치가 '${bundle.title}' 번들에 댓글을 남겼어요",
                body = content,
                target = NotificationTarget(
                    kind = NotificationTargetKind.BUNDLE_COMMENTS,
                    bunchId = inbox.first,
                    bundleId = bundle.id,
                    targetBunchId = bundle.bunchId,
                    sourceBunchId = sourceBunch.id
                ),
                createdAt = now
            )
        })
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun bundleLiked(bundle: Bundle, sourceBunch: Bunch, actorId: Long) {
        if (sourceBunch.id == bundle.bunchId) return
        val participantIds = participants.findAllByBundleIdOrderByJoinedAtAscIdAsc(bundle.id)
            .map { it.memberId }.toSet()
        val now = clock.instant()
        saveNotifications(memberships.findActiveMembers(bundle.bunchId)
            .filter { it.member.id != actorId && it.member.id in participantIds }
            .sortedBy { it.member.id }
            .map {
                Notification(
                    recipientMemberId = it.member.id,
                    bunchId = bundle.bunchId,
                    type = NotificationType.BUNDLE_LIKED,
                    dedupeKey = "BUNDLE_LIKED:${sourceBunch.id}:${bundle.id}",
                    title = "${sourceBunch.name} 번치가 우리 번들을 좋아해요",
                    body = bundle.title.ifBlank { "제목 미정" },
                    target = NotificationTarget(
                        kind = NotificationTargetKind.BUNDLE,
                        bunchId = bundle.bunchId,
                        bundleId = bundle.id,
                        targetBunchId = bundle.bunchId,
                        sourceBunchId = sourceBunch.id
                    ),
                    createdAt = now
                )
            })
    }

    private fun saveNotifications(rows: List<Notification>) {
        val saved = notifications.saveAll(rows)
        pushJobs.enqueue(saved, clock.instant())
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
        saveNotifications(recipients.distinct().filter { it != actorId && it in current }.sorted().map {
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
