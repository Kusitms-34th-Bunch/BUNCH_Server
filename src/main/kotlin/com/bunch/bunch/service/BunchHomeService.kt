package com.bunch.bunch.service

import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bunch.domain.MembershipRole
import com.bunch.bunch.dto.BunchHomeResponse
import com.bunch.bunch.dto.BunchPermissions
import com.bunch.bunch.dto.BunchHomeProfileResponse
import com.bunch.bunch.dto.toMyMembershipResponse
import com.bunch.bundle.service.BundleService
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.member.domain.MemberRepository
import com.bunch.notification.domain.NotificationRepository
import com.bunch.bunch.domain.BunchFollowRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

@Service
class BunchHomeService(
    private val members: MemberRepository,
    private val memberships: BunchMembershipRepository,
    private val notices: BunchAccessNoticeService,
    private val bundles: BundleService,
    private val follows: BunchFollowRepository,
    private val notifications: NotificationRepository,
    private val clock: Clock
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun home(memberId: Long, bunchId: Long): BunchHomeResponse {
        members.findById(memberId).orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }
            .requireCompleted()
        notices.requireMemberOrAccessNotice(memberId, bunchId)
        val membership = memberships.findByMemberIdAndBunchIdAndStatus(memberId, bunchId)
            ?: fail(ErrorCode.FORBIDDEN)
        val bunch = membership.bunch
        val summary = bundles.summary(memberId, bunchId)
        val cutoff = clock.instant().minus(RETENTION)
        val unreadCount = notifications.countByRecipientMemberIdAndBunchIdAndCreatedAtAfterAndReadAtIsNull(
            memberId,
            bunchId,
            cutoff
        )

        return BunchHomeResponse(
            bunch = BunchHomeProfileResponse.from(bunch),
            myMembership = membership.toMyMembershipResponse(),
            permissions = permissions(membership.role),
            profileVersion = bunch.profileVersion,
            membershipVersion = bunch.membershipVersion,
            memberCount = memberships.countByBunchIdAndStatus(bunchId),
            followerCount = follows.countFollowers(bunchId, bunchId),
            followingCount = follows.countFollowing(bunchId, bunchId),
            draftCount = summary.draftCount,
            publishedCount = summary.publishedCount,
            stage = summary.stage,
            unreadCount = unreadCount
        )
    }

    private fun permissions(role: MembershipRole) = BunchPermissions(
        canEditProfile = role == MembershipRole.OWNER,
        canDeleteBunch = role == MembershipRole.OWNER,
        canManageRoles = role == MembershipRole.OWNER,
        canTransferOwner = role == MembershipRole.OWNER,
        canBlock = role == MembershipRole.OWNER || role == MembershipRole.CO_OWNER,
        canInvite = true,
        canLeave = true
    )

    private fun fail(code: ErrorCode): Nothing = throw BusinessException(code)


    companion object {
        private val RETENTION: Duration = Duration.ofDays(30)
    }
}
