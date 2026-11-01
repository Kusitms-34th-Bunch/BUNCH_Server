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
import com.bunch.bunch.dto.ChangeMembershipRoleRequest
import com.bunch.bunch.dto.ManagedMembershipResponse
import com.bunch.bunch.dto.MembershipActions
import com.bunch.bunch.dto.MembershipMutationResult
import com.bunch.bunch.dto.MembershipsResponse
import com.bunch.bunch.dto.OwnerTransferRequest
import com.bunch.bunch.dto.toMyMembershipResponse
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.member.domain.MemberRepository
import com.bunch.notification.domain.Notification
import com.bunch.notification.domain.NotificationRepository
import com.bunch.notification.domain.NotificationTargetKind
import com.bunch.notification.domain.NotificationType
import com.bunch.notification.dto.NotificationTarget
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class BunchMembershipManagementService(
    private val bunches: BunchRepository,
    private val memberships: BunchMembershipRepository,
    private val actionRequests: BunchActionRequestRepository,
    private val members: MemberRepository,
    private val notifications: NotificationRepository,
    private val clock: Clock
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun memberships(userId: Long, bunchId: Long): MembershipsResponse {
        validatePositiveId(bunchId)
        requireCompleted(userId)
        val bunch = bunches.findById(bunchId).orElseThrow { BusinessException(ErrorCode.RESOURCE_NOT_FOUND) }
        val current = memberships.findByMemberIdAndBunchIdAndStatus(userId, bunchId)
            ?: fail(ErrorCode.FORBIDDEN)
        val activeMembers = memberships.findActiveMembers(bunchId)

        return MembershipsResponse(
            bunchId = bunch.id,
            membershipVersion = bunch.membershipVersion,
            myMembership = current.toMyMembershipResponse(),
            members = activeMembers.map { membership ->
                ManagedMembershipResponse(
                    membershipId = membership.id,
                    memberId = membership.member.id,
                    nickname = membership.member.nickname,
                    imageUrl = membership.member.imageUrl,
                    role = membership.role,
                    isMe = membership.member.id == userId,
                    joinedAt = membership.joinedAt,
                    actions = actionsFor(current, membership, userId)
                )
            }
        )
    }

    @Transactional
    fun changeRole(
        userId: Long,
        bunchId: Long,
        targetMembershipId: Long,
        request: ChangeMembershipRoleRequest
    ): MembershipMutationResult {
        validatePositiveId(bunchId)
        validatePositiveId(targetMembershipId)
        validatePositiveId(request.actorMembershipId)
        val bunch = lockContext(userId, bunchId)
        validateVersionInput(request.expectedMembershipVersion)
        val actor = requireActor(bunch, userId, request.actorMembershipId)
        val target = requireTarget(bunch, targetMembershipId)

        requireOwner(actor)
        requireSingleOwner(bunch)
        if (actor.id == target.id) fail(ErrorCode.INVALID_INPUT)
        if (request.role == MembershipRole.OWNER) fail(ErrorCode.INVALID_INPUT)
        if (target.role == MembershipRole.OWNER) fail(ErrorCode.FORBIDDEN)
        if (target.status != MembershipStatus.ACTIVE) {
            failMembershipVersion(bunch)
        }

        if (target.role == request.role) {
            if (request.expectedMembershipVersion > bunch.membershipVersion) {
                failMembershipVersion(bunch)
            }
            return mutationResult(bunch, emptyList())
        }

        requireCurrentVersion(bunch, request.expectedMembershipVersion)
        val now = clock.instant()
        target.changeRole(request.role, actor.member.id, now)
        bunch.membershipVersion++
        memberships.flush()

        val notificationType = when (request.role) {
            MembershipRole.CO_OWNER -> NotificationType.CO_OWNER_GRANTED
            MembershipRole.MEMBER -> NotificationType.CO_OWNER_REVOKED
            MembershipRole.OWNER -> fail(ErrorCode.INVALID_INPUT)
        }
        writeRoleNotification(
            bunch = bunch,
            recipient = target,
            actorMemberId = actor.member.id,
            type = notificationType,
            version = bunch.membershipVersion,
            now = now
        )

        return mutationResult(bunch, listOf(target.id))
    }

    @Transactional
    fun transferOwner(
        userId: Long,
        bunchId: Long,
        requestKey: UUID,
        request: OwnerTransferRequest
    ): MembershipMutationResult {
        validatePositiveId(bunchId)
        validatePositiveId(request.actorMembershipId)
        validatePositiveId(request.targetMembershipId)
        validateVersionInput(request.expectedMembershipVersion)
        requireCompleted(userId)
        val now = clock.instant()
        actionRequests.deleteExpiredByBunchId(bunchId, now)
        actionRequests.flush()
        val fingerprint = fingerprint(
            request.actorMembershipId,
            request.expectedMembershipVersion,
            request.targetMembershipId
        )
        actionRequests.findActiveByMemberIdAndBunchIdAndOperationAndRequestKey(
            memberId = userId,
            bunchId = bunchId,
            operation = BunchActionOperation.OWNER_TRANSFER,
            requestKey = requestKey,
            now = now
        )?.let { previous ->
            if (previous.fingerprint != fingerprint) fail(ErrorCode.IDEMPOTENCY_CONFLICT)
            return mutationResultFrom(previous.result)
        }

        val bunch = lockContext(userId, bunchId)
        actionRequests.findActiveByMemberIdAndBunchIdAndOperationAndRequestKey(
            memberId = userId,
            bunchId = bunchId,
            operation = BunchActionOperation.OWNER_TRANSFER,
            requestKey = requestKey,
            now = now
        )?.let { previous ->
            if (previous.fingerprint != fingerprint) fail(ErrorCode.IDEMPOTENCY_CONFLICT)
            return mutationResultFrom(previous.result)
        }

        val actor = requireActor(bunch, userId, request.actorMembershipId)
        val target = requireTarget(bunch, request.targetMembershipId)
        requireOwner(actor)
        requireSingleOwner(bunch)
        if (actor.id == target.id) fail(ErrorCode.INVALID_INPUT)
        if (target.status != MembershipStatus.ACTIVE) failMembershipVersion(bunch)
        if (target.role == MembershipRole.OWNER) fail(ErrorCode.FORBIDDEN)
        requireCurrentVersion(bunch, request.expectedMembershipVersion)

        actor.changeRole(MembershipRole.MEMBER, actor.member.id, now)
        memberships.flush()
        target.changeRole(MembershipRole.OWNER, actor.member.id, now)
        bunch.membershipVersion++
        memberships.flush()

        val result = mutationResult(bunch, listOf(actor.id, target.id))
        actionRequests.saveAndFlush(
            BunchActionRequest(
                memberId = userId,
                bunchId = bunchId,
                actorMembershipId = actor.id,
                operation = BunchActionOperation.OWNER_TRANSFER,
                requestKey = requestKey,
                fingerprint = fingerprint,
                result = resultMap(result),
                createdAt = now,
                expiresAt = now.plus(Duration.ofDays(30))
            )
        )
        writeRoleNotification(
            bunch = bunch,
            recipient = target,
            actorMemberId = actor.member.id,
            type = NotificationType.OWNER_TRANSFERRED,
            version = bunch.membershipVersion,
            now = now
        )

        return result
    }

    private fun actionsFor(
        viewer: BunchMembership,
        target: BunchMembership,
        userId: Long
    ) = if (target.member.id == userId) {
        MembershipActions(false, false, false, false)
    } else {
        MembershipActions(
            canGrantCoOwner = viewer.role == MembershipRole.OWNER && target.role == MembershipRole.MEMBER,
            canRevokeCoOwner = viewer.role == MembershipRole.OWNER && target.role == MembershipRole.CO_OWNER,
            canTransferOwner = viewer.role == MembershipRole.OWNER && target.role != MembershipRole.OWNER,
            canRemove = when (viewer.role) {
                MembershipRole.OWNER -> target.role != MembershipRole.OWNER
                MembershipRole.CO_OWNER -> target.role == MembershipRole.MEMBER
                MembershipRole.MEMBER -> false
            }
        )
    }

    private fun lockContext(userId: Long, bunchId: Long): Bunch {
        members.findLockedById(userId)?.requireCompleted() ?: fail(ErrorCode.USER_NOT_FOUND)
        return bunches.findLocked(bunchId) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
    }

    private fun requireActor(
        bunch: Bunch,
        userId: Long,
        actorMembershipId: Long
    ): BunchMembership {
        val actor = memberships.findById(actorMembershipId).orElse(null)
        if (
            actor == null ||
            actor.bunch.id != bunch.id ||
            actor.member.id != userId ||
            actor.status != MembershipStatus.ACTIVE
        ) {
            fail(ErrorCode.FORBIDDEN)
        }
        return actor
    }

    private fun requireTarget(bunch: Bunch, targetMembershipId: Long): BunchMembership {
        val target = memberships.findById(targetMembershipId).orElse(null)
        if (target == null || target.bunch.id != bunch.id) fail(ErrorCode.RESOURCE_NOT_FOUND)
        return target
    }

    private fun requireOwner(actor: BunchMembership) {
        if (actor.role != MembershipRole.OWNER) fail(ErrorCode.FORBIDDEN)
    }

    private fun requireSingleOwner(bunch: Bunch) {
        val owners = memberships.findAllByBunchIdAndStatus(bunch.id)
            .count { it.role == MembershipRole.OWNER }
        if (owners != 1) fail(ErrorCode.INTERNAL_ERROR)
    }

    private fun requireCurrentVersion(bunch: Bunch, expectedVersion: Long) {
        if (bunch.membershipVersion != expectedVersion) failMembershipVersion(bunch)
    }

    private fun failMembershipVersion(bunch: Bunch): Nothing = throw BusinessException(
        ErrorCode.BUNCH_MEMBERSHIP_VERSION_CONFLICT,
        mapOf("membershipVersion" to bunch.membershipVersion)
    )

    private fun validateVersionInput(expectedVersion: Long) {
        if (expectedVersion < 0) fail(ErrorCode.INVALID_INPUT)
    }

    private fun validatePositiveId(id: Long) {
        if (id <= 0) fail(ErrorCode.INVALID_INPUT)
    }

    private fun writeRoleNotification(
        bunch: Bunch,
        recipient: BunchMembership,
        actorMemberId: Long,
        type: NotificationType,
        version: Long,
        now: Instant
    ) {
        if (recipient.member.id == actorMemberId) return
        val (title, body, dedupeKey) = when (type) {
            NotificationType.CO_OWNER_GRANTED -> Triple(
                "${bunch.name} 번치의 공동 관리자가 되었어요.",
                "번치를 함께 관리할 수 있어요.",
                "CO_OWNER_GRANTED:${recipient.id}:$version"
            )
            NotificationType.CO_OWNER_REVOKED -> Triple(
                "공동 관리자 권한이 해제되었어요.",
                "이제 일반 멤버로 번치에 함께해요.",
                "CO_OWNER_REVOKED:${recipient.id}:$version"
            )
            NotificationType.OWNER_TRANSFERRED -> Triple(
                "${bunch.name} 번치의 관리자가 되었어요",
                "관리자 권한으로 번치를 자유롭게 관리해 보세요.",
                "OWNER_TRANSFERRED:${recipient.id}:$version"
            )
            else -> fail(ErrorCode.INTERNAL_ERROR)
        }
        notifications.saveAndFlush(
            Notification(
                recipientMemberId = recipient.member.id,
                bunchId = bunch.id,
                type = type,
                dedupeKey = dedupeKey,
                title = title,
                body = body,
                target = NotificationTarget(NotificationTargetKind.ROLE_PERMISSIONS, bunch.id),
                createdAt = now
            )
        )
    }

    private fun mutationResult(bunch: Bunch, changedMembershipIds: List<Long>) = MembershipMutationResult(
        bunchId = bunch.id,
        membershipVersion = bunch.membershipVersion,
        changedMembershipIds = changedMembershipIds,
        ownerMembershipId = memberships.findAllByBunchIdAndStatus(bunch.id)
            .single { it.role == MembershipRole.OWNER }
            .id
    )

    private fun resultMap(result: MembershipMutationResult): Map<String, Any?> = mapOf(
        "bunchId" to result.bunchId,
        "membershipVersion" to result.membershipVersion,
        "changedMembershipIds" to result.changedMembershipIds,
        "ownerMembershipId" to result.ownerMembershipId
    )

    private fun mutationResultFrom(result: Map<String, Any?>): MembershipMutationResult = MembershipMutationResult(
        bunchId = result.longValue("bunchId"),
        membershipVersion = result.longValue("membershipVersion"),
        changedMembershipIds = result.longValues("changedMembershipIds"),
        ownerMembershipId = result.longValue("ownerMembershipId")
    )

    private fun requireCompleted(userId: Long) {
        members.findById(userId).orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }
            .requireCompleted()
    }

    private fun fingerprint(vararg values: Any): String = MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString("|").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun fail(errorCode: ErrorCode): Nothing = throw BusinessException(errorCode)
}

private fun Map<String, Any?>.longValue(key: String): Long =
    (this[key] as? Number)?.toLong() ?: throw BusinessException(ErrorCode.INTERNAL_ERROR)

private fun Map<String, Any?>.longValues(key: String): List<Long> =
    (this[key] as? List<*>)?.map {
        (it as? Number)?.toLong() ?: throw BusinessException(ErrorCode.INTERNAL_ERROR)
    } ?: throw BusinessException(ErrorCode.INTERNAL_ERROR)
