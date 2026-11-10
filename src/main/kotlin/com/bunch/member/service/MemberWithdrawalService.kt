package com.bunch.member.service

import com.bunch.bunch.domain.BunchActionRequestRepository
import com.bunch.bunch.domain.BunchMembership
import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.domain.MembershipRole
import com.bunch.bunch.dto.BunchDeletionRequest
import com.bunch.bunch.dto.BunchMembershipExitRequest
import com.bunch.bunch.service.BunchAccessNoticeService
import com.bunch.bunch.service.BunchDeletionService
import com.bunch.bunch.service.BunchMembershipExitService
import com.bunch.bundle.service.BundleService
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.image.service.ImageAssetService
import com.bunch.member.domain.ExternalIdentityRepository
import com.bunch.member.domain.MemberRepository
import com.bunch.member.dto.WithdrawalCheckResponse
import com.bunch.member.dto.WithdrawalOwnerBunch
import com.bunch.notification.service.NotificationService
import com.bunch.notification.service.PushDeviceService
import com.bunch.notification.service.PushPreferenceService
import com.bunch.social.service.SocialCommentService
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

@Service
class MemberWithdrawalService(
    private val members: MemberRepository,
    private val memberships: BunchMembershipRepository,
    private val bunches: BunchRepository,
    private val actionRequests: BunchActionRequestRepository,
    private val exits: BunchMembershipExitService,
    private val deletions: BunchDeletionService,
    private val identities: ExternalIdentityRepository,
    private val socialComments: SocialCommentService,
    private val bundles: BundleService,
    private val pushDevices: PushDeviceService,
    private val pushPreferences: PushPreferenceService,
    private val notifications: NotificationService,
    private val accessNotices: BunchAccessNoticeService,
    private val imageAssets: ImageAssetService,
    private val sessions: RefreshTokenRepository,
    private val entityManager: EntityManager
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // 7.6.2: asked on every "next" tap, so the answer is always read from the server.
    @Transactional(readOnly = true)
    fun check(memberId: Long): WithdrawalCheckResponse {
        members.findById(memberId)
            .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }
            .requireCompleted()
        val owned = memberships.findAllByMemberIdAndStatusOrderByBunchId(memberId)
            .filter { it.role == MembershipRole.OWNER && memberships.countByBunchIdAndStatus(it.bunch.id) > 1 }
        return WithdrawalCheckResponse(owned.map { it.toOwnerBunch() })
    }

    // 7.6.4: one transaction, so a failure anywhere leaves the account as it was. Sessions go after the commit.
    @Transactional
    fun withdraw(memberId: Long) {
        members.findLockedById(memberId)?.requireCompleted()
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)

        // Bunches are locked in ascending ID order and judged again under the lock (7.6.4 rule 1).
        val plans = memberships.findAllByMemberIdAndStatusOrderByBunchId(memberId).mapNotNull { membership ->
            val bunch = bunches.findLocked(membership.bunch.id) ?: return@mapNotNull null
            Plan(
                bunchId = bunch.id,
                membershipId = membership.id,
                role = membership.role,
                membershipVersion = bunch.membershipVersion,
                alone = memberships.countByBunchIdAndStatus(bunch.id) <= 1,
                owner = membership.toOwnerBunch()
            )
        }
        val handoverRequired = plans.filter { it.role == MembershipRole.OWNER && !it.alone }
        if (handoverRequired.isNotEmpty()) {
            throw BusinessException(
                ErrorCode.MEMBER_WITHDRAWAL_OWNER_REQUIRED,
                mapOf("bunches" to handoverRequired.map { it.owner })
            )
        }

        plans.forEach { leaveOrDelete(memberId, it) }

        // The statements below delete rows that entities loaded by the bunch work still describe, so those go first.
        entityManager.flush()
        entityManager.clear()

        socialComments.deleteAllOf(memberId)
        bundles.clearCommentsOf(memberId)
        pushDevices.removeAllOf(memberId)
        pushPreferences.removeAllOf(memberId)
        notifications.removeAllOf(memberId)
        accessNotices.removeAllOf(memberId)
        actionRequests.deleteByMemberId(memberId)
        identities.deleteByMemberId(memberId)

        // Deleting a bunch clears the persistence context, so the account is read again before it is changed.
        val member = members.findLockedById(memberId) ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
        val profileImageId = member.profileImageAsset?.id
        member.withdraw()
        members.saveAndFlush(member)
        imageAssets.releaseAfterCommit(profileImageId)
        revokeSessionsAfterCommit(memberId)
    }

    // A bunch the main manager is alone in is deleted; any other bunch is left as a normal member would (5.8.1).
    private fun leaveOrDelete(memberId: Long, plan: Plan) {
        if (plan.role == MembershipRole.OWNER && plan.alone) {
            deletions.delete(
                memberId,
                plan.bunchId,
                UUID.randomUUID(),
                BunchDeletionRequest(
                    plan.membershipId,
                    plan.membershipVersion,
                    BunchDeletionService.DELETE_CONFIRMATION
                )
            )
        } else {
            exits.leave(
                memberId,
                plan.bunchId,
                UUID.randomUUID(),
                BunchMembershipExitRequest(plan.membershipId, plan.membershipVersion)
            )
        }
    }

    // Redis is not part of the transaction: a failed commit must not log the member out, and a failed purge
    // leaves sessions that every API already refuses because the account is withdrawn.
    private fun revokeSessionsAfterCommit(memberId: Long) {
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                try {
                    sessions.deleteAll(memberId)
                } catch (_: RuntimeException) {
                    logger.warn("Sessions of a withdrawn account could not be removed: memberId={}", memberId)
                }
            }
        })
    }

    private fun BunchMembership.toOwnerBunch() = WithdrawalOwnerBunch(
        bunchId = bunch.id,
        name = bunch.name,
        imageUrl = bunch.imageUrl,
        myMembershipId = id,
        membershipVersion = bunch.membershipVersion
    )

    private data class Plan(
        val bunchId: Long,
        val membershipId: Long,
        val role: MembershipRole,
        val membershipVersion: Long,
        val alone: Boolean,
        val owner: WithdrawalOwnerBunch
    )
}
