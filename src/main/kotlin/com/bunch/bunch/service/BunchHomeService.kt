package com.bunch.bunch.service

import com.bunch.bunch.domain.BunchAccessScope
import com.bunch.bunch.domain.BunchFollowRepository
import com.bunch.bunch.domain.BunchPublicRepository
import com.bunch.bunch.domain.MembershipRole
import com.bunch.bunch.dto.BunchHomeProfileResponse
import com.bunch.bunch.dto.BunchHomeResponse
import com.bunch.bunch.dto.BunchPermissions
import com.bunch.bunch.dto.FollowResult
import com.bunch.bunch.dto.toMyMembershipResponse
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.domain.BundleStatus
import com.bunch.bundle.service.BundleService
import com.bunch.bundle.service.BundleStagePolicy
import com.bunch.notification.domain.NotificationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

@Service
class BunchHomeService(
    private val access: BunchSocialAccessService,
    private val bundles: BundleService,
    private val bundleRepository: BundleRepository,
    private val publicRepository: BunchPublicRepository,
    private val stagePolicy: BundleStagePolicy,
    private val follows: BunchFollowRepository,
    private val notifications: NotificationRepository,
    private val clock: Clock
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun home(memberId: Long, bunchId: Long): BunchHomeResponse {
        val current = access.current(memberId)
        val bunch = access.target(memberId, bunchId)
        val currentId = current?.bunch?.id
        val internal = currentId == bunch.id
        val canView = access.canView(bunch, currentId)
        val summary = if (internal) bundles.summary(memberId, bunchId) else null
        val stage = summary?.stage ?: stagePolicy.stage(
            bundleRepository.countByBunchIdAndStatus(bunchId, BundleStatus.PUBLISHED)
        )
        val relation = currentId?.let { follows.findByFollowerBunchIdAndFollowedBunchId(it, bunchId) }
        val follow = FollowResult.from(relation)
        val unread = if (internal) {
            notifications.countByRecipientMemberIdAndBunchIdAndCreatedAtAfterAndReadAtIsNull(
                memberId,
                bunchId,
                clock.instant().minus(RETENTION)
            )
        } else null

        return BunchHomeResponse(
            bunch = BunchHomeProfileResponse.from(bunch),
            myMembership = current?.takeIf { internal }?.toMyMembershipResponse(),
            permissions = permissions(current?.role, internal),
            profileVersion = bunch.profileVersion.takeIf { internal },
            membershipVersion = bunch.membershipVersion.takeIf { internal },
            memberCount = publicRepository.countActiveMembers(bunchId),
            followerCount = follows.countFollowers(bunchId, currentId),
            followingCount = follows.countFollowing(bunchId, currentId),
            draftCount = summary?.draftCount,
            publishedCount = summary?.publishedCount,
            stage = stage,
            unreadCount = unread,
            currentBunchId = currentId,
            accessScope = if (internal) BunchAccessScope.INTERNAL else BunchAccessScope.EXTERNAL,
            followState = follow.followState,
            followId = follow.followId,
            canViewBundles = canView,
            canViewMembers = canView,
            canViewRelations = canView,
            isCurrentBunch = internal
        )
    }

    private fun permissions(role: MembershipRole?, internal: Boolean) = BunchPermissions(
        canEditProfile = internal && role == MembershipRole.OWNER,
        canDeleteBunch = internal && role == MembershipRole.OWNER,
        canManageRoles = internal && role == MembershipRole.OWNER,
        canTransferOwner = internal && role == MembershipRole.OWNER,
        canBlock = role == MembershipRole.OWNER || role == MembershipRole.CO_OWNER,
        canInvite = internal,
        canLeave = internal
    )

    companion object {
        private val RETENTION: Duration = Duration.ofDays(30)
    }
}
