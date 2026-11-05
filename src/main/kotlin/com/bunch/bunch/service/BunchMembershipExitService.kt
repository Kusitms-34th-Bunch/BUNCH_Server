package com.bunch.bunch.service

import com.bunch.bunch.domain.Bunch
import com.bunch.bunch.domain.BunchActionOperation
import com.bunch.bunch.domain.BunchActionRequest
import com.bunch.bunch.domain.BunchActionRequestRepository
import com.bunch.bunch.domain.BunchMembership
import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.domain.MembershipRole
import com.bunch.bunch.domain.MembershipStatus
import com.bunch.bunch.dto.BunchDepartureResponse
import com.bunch.bunch.dto.BunchMembershipExitRequest
import com.bunch.bunch.dto.BunchRemovalRequest
import com.bunch.bunch.dto.BunchRemovalResponse
import com.bunch.bundle.service.BundleService
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.member.domain.Member
import com.bunch.member.domain.MemberRepository
import com.bunch.notification.service.NotificationWriter
import com.bunch.notification.service.PushJobWriter
import com.bunch.notification.domain.NotificationType
import com.bunch.notification.domain.NotificationTargetKind
import com.bunch.notification.dto.NotificationTarget
import com.bunch.photo.service.PhotoService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class BunchMembershipExitService(
    private val bunches: BunchRepository,
    private val memberships: BunchMembershipRepository,
    private val actionRequests: BunchActionRequestRepository,
    private val members: MemberRepository,
    private val invitations: InvitationService,
    private val bundles: BundleService,
    private val photos: PhotoService,
    private val accessNotices: BunchAccessNoticeService,
    private val notifications: NotificationWriter,
    private val pushJobs: PushJobWriter,
    private val clock: Clock
) {
    @Transactional
    fun leave(
        userId: Long,
        bunchId: Long,
        requestKey: UUID,
        request: BunchMembershipExitRequest
    ): BunchDepartureResponse {
        val actorMember = lockMember(userId)
        val bunch = lockBunch(bunchId)
        validateVersionInput(request.expectedMembershipVersion)
        val fingerprint = fingerprint(
            request.actorMembershipId,
            request.expectedMembershipVersion,
            request.successorMembershipId
        )
        replayLeave(userId, bunch, requestKey, fingerprint)?.let { return it }

        val actor = requireActor(bunch, userId, request.actorMembershipId)
        requireCurrentVersion(bunch, request.expectedMembershipVersion)
        val now = clock.instant()
        val successor = validateSuccessor(bunch, actor, request.successorMembershipId)

        if (successor != null) {
            actor.changeRole(MembershipRole.MEMBER, actorMember.id, now)
            memberships.flush()
            successor.changeRole(MembershipRole.OWNER, actorMember.id, now)
            memberships.flush()
        }

        bundles.memberDeparted(bunch.id, actor.member.id)
        photos.removeAllUploaders(bunch.id, actor.member.id)
        pushJobs.cancelCommentRemindersForBunchLeave(bunch.id, actor.member.id)
        actor.end(MembershipStatus.LEFT, now)
        bunch.membershipVersion++
        memberships.flush()

        val defaultBunchId = updateDefaultBunch(actor.member.id, now)
        val result = BunchDepartureResponse(
            bunchId = bunch.id,
            departedMembershipId = actor.id,
            outcome = "LEFT",
            defaultBunchId = defaultBunchId
        )
        actionRequests.saveAndFlush(
            BunchActionRequest(
                memberId = userId,
                bunchId = bunch.id,
                actorMembershipId = actor.id,
                operation = BunchActionOperation.LEAVE,
                requestKey = requestKey,
                fingerprint = fingerprint,
                result = resultMap(result),
                createdAt = now,
                expiresAt = now.plus(ACTION_REQUEST_RETENTION)
            )
        )
        if (successor != null) {
            notifications.ownerTransferred(
                bunchId = bunch.id,
                membershipId = successor.id,
                recipientMemberId = successor.member.id,
                version = bunch.membershipVersion,
                bunchName = bunch.name,
                now = now
            )
        }
        return result
    }

    @Transactional
    fun remove(
        userId: Long,
        bunchId: Long,
        targetMembershipId: Long,
        request: BunchRemovalRequest
    ): BunchRemovalResponse {
        val targetMemberId = memberships.findMemberIdByIdAndBunchId(targetMembershipId, bunchId)
            ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
        val lockedMembers = lockMembers(listOf(userId, targetMemberId))
        val actorMember = lockedMembers.first { it.id == userId }
        actorMember.requireCompleted()
        val bunch = lockBunch(bunchId)
        validateVersionInput(request.expectedMembershipVersion)
        val actor = requireActor(bunch, userId, request.actorMembershipId)
        val target = memberships.findByIdAndBunchId(targetMembershipId, bunchId)
            ?: fail(ErrorCode.RESOURCE_NOT_FOUND)

        if (target.status == MembershipStatus.REMOVED) {
            requireCanRemove(actor, target)
            return removalResult(bunch, target.id)
        }
        if (target.status != MembershipStatus.ACTIVE) fail(ErrorCode.FORBIDDEN)
        requireCurrentVersion(bunch, request.expectedMembershipVersion)
        requireCanRemove(actor, target)

        val now = clock.instant()
        if (target.role == MembershipRole.CO_OWNER) {
            target.changeRole(MembershipRole.MEMBER, actorMember.id, now)
            memberships.flush()
        }
        val notice = accessNotices.recordRemoval(bunch, target, now)
        bundles.memberDeparted(bunch.id, target.member.id)
        photos.removeAllUploaders(bunch.id, target.member.id)
        pushJobs.cancelCommentRemindersForBunchLeave(bunch.id, target.member.id)
        target.end(MembershipStatus.REMOVED, now)
        bunch.membershipVersion++
        memberships.flush()
        pushJobs.enqueueDetached(
            recipientMemberIds = listOf(target.member.id),
            bunchId = bunch.id,
            type = NotificationType.MEMBER_REMOVED,
            dedupeKey = "MEMBER_REMOVED:${target.id}",
            title = "${bunch.name} 번치에서 내보내졌어요",
            body = "번치 멤버 목록에서 제외되어 더 이상 번치에 참여할 수 없어요.",
            target = NotificationTarget(
                kind = NotificationTargetKind.ACCESS_NOTICE,
                bunchId = bunch.id,
                noticeId = notice.id
            ),
            now = now
        )
        invitations.rotateAfterRemoval(bunch.id, actorMember.id, now)
        return removalResult(bunch, target.id)
    }

    private fun lockMember(memberId: Long): Member =
        members.findLockedById(memberId)?.requireCompleted()
            ?: fail(ErrorCode.USER_NOT_FOUND)

    private fun lockMembers(memberIds: Collection<Long>): List<Member> =
        memberIds.distinct().sorted().map { memberId ->
            members.findLockedById(memberId) ?: fail(ErrorCode.USER_NOT_FOUND)
        }

    private fun lockBunch(bunchId: Long): Bunch =
        bunches.findLocked(bunchId) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)

    private fun requireActor(
        bunch: Bunch,
        userId: Long,
        actorMembershipId: Long
    ): BunchMembership {
        val actor = memberships.findByIdAndBunchId(actorMembershipId, bunch.id)
        if (
            actor == null ||
            actor.member.id != userId ||
            actor.status != MembershipStatus.ACTIVE
        ) {
            fail(ErrorCode.FORBIDDEN)
        }
        return actor
    }

    private fun validateSuccessor(
        bunch: Bunch,
        actor: BunchMembership,
        successorMembershipId: Long?
    ): BunchMembership? {
        if (actor.role != MembershipRole.OWNER) {
            if (successorMembershipId != null) fail(ErrorCode.INVALID_INPUT)
            return null
        }
        if (memberships.countByBunchIdAndStatus(bunch.id) <= 1) {
            fail(ErrorCode.BUNCH_SOLE_MEMBER_CANNOT_LEAVE)
        }
        val successorId = successorMembershipId ?: fail(ErrorCode.BUNCH_OWNER_SUCCESSOR_REQUIRED)
        if (successorId == actor.id) fail(ErrorCode.INVALID_INPUT)
        val successor = memberships.findByIdAndBunchId(successorId, bunch.id)
            ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
        if (successor.status != MembershipStatus.ACTIVE) fail(ErrorCode.FORBIDDEN)
        if (successor.role == MembershipRole.OWNER) fail(ErrorCode.INTERNAL_ERROR)
        return successor
    }

    private fun requireCanRemove(actor: BunchMembership, target: BunchMembership) {
        if (actor.id == target.id) fail(ErrorCode.INVALID_INPUT)
        when (actor.role) {
            MembershipRole.OWNER -> if (target.role == MembershipRole.OWNER) fail(ErrorCode.FORBIDDEN)
            MembershipRole.CO_OWNER -> if (target.role != MembershipRole.MEMBER) fail(ErrorCode.FORBIDDEN)
            MembershipRole.MEMBER -> fail(ErrorCode.FORBIDDEN)
        }
    }

    private fun requireCurrentVersion(bunch: Bunch, expectedVersion: Long) {
        if (bunch.membershipVersion != expectedVersion) {
            throw BusinessException(
                ErrorCode.BUNCH_MEMBERSHIP_VERSION_CONFLICT,
                mapOf("membershipVersion" to bunch.membershipVersion)
            )
        }
    }

    private fun validateVersionInput(expectedVersion: Long) {
        if (expectedVersion < 0) fail(ErrorCode.INVALID_INPUT)
    }

    private fun replayLeave(
        userId: Long,
        bunch: Bunch,
        requestKey: UUID,
        fingerprint: String
    ): BunchDepartureResponse? {
        val previous = actionRequests.findByMemberIdAndBunchIdAndOperationAndRequestKey(
            userId,
            bunch.id,
            BunchActionOperation.LEAVE,
            requestKey
        ) ?: return null
        val now = clock.instant()
        if (!now.isBefore(previous.expiresAt)) {
            actionRequests.delete(previous)
            actionRequests.flush()
            return null
        }
        if (previous.fingerprint != fingerprint) fail(ErrorCode.IDEMPOTENCY_CONFLICT)
        return departureFrom(previous.result).copy(
            defaultBunchId = updateDefaultBunch(userId, now)
        )
    }

    private fun updateDefaultBunch(memberId: Long, now: Instant): Long? {
        val current = memberships.findFirstByMemberIdAndStatusOrderByLastUsedAtDescIdDesc(memberId)
            ?: return null
        current.lastUsedAt = now
        return current.bunch.id
    }

    private fun removalResult(bunch: Bunch, membershipId: Long) = BunchRemovalResponse(
        bunchId = bunch.id,
        removedMembershipId = membershipId,
        membershipVersion = bunch.membershipVersion,
        memberCount = memberships.countByBunchIdAndStatus(bunch.id)
    )

    private fun resultMap(result: BunchDepartureResponse): Map<String, Any?> = mapOf(
        "bunchId" to result.bunchId,
        "departedMembershipId" to result.departedMembershipId,
        "outcome" to result.outcome,
        "defaultBunchId" to result.defaultBunchId
    )

    private fun departureFrom(result: Map<String, Any?>) = BunchDepartureResponse(
        bunchId = result.longValue("bunchId"),
        departedMembershipId = result.longValue("departedMembershipId"),
        outcome = result.stringValue("outcome"),
        defaultBunchId = result.longValueOrNull("defaultBunchId")
    )

    private fun fingerprint(vararg values: Any?): String = MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString("|") { it?.toString() ?: "null" }.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun fail(errorCode: ErrorCode): Nothing = throw BusinessException(errorCode)

    companion object {
        private val ACTION_REQUEST_RETENTION: Duration = Duration.ofDays(30)
    }
}

private fun Map<String, Any?>.longValue(key: String): Long =
    (this[key] as? Number)?.toLong() ?: throw BusinessException(ErrorCode.INTERNAL_ERROR)

private fun Map<String, Any?>.longValueOrNull(key: String): Long? =
    (this[key] as? Number)?.toLong()

private fun Map<String, Any?>.stringValue(key: String): String =
    this[key] as? String ?: throw BusinessException(ErrorCode.INTERNAL_ERROR)
