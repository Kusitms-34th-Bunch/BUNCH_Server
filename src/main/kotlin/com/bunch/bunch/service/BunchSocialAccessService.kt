package com.bunch.bunch.service

import com.bunch.bunch.domain.Bunch
import com.bunch.bunch.domain.BunchBlockRepository
import com.bunch.bunch.domain.BunchFollowRepository
import com.bunch.bunch.domain.BunchFollowStatus
import com.bunch.bunch.domain.BunchMembership
import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.domain.Visibility
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.member.domain.MemberRepository
import org.springframework.stereotype.Service

@Service
class BunchSocialAccessService(
    private val bunches: BunchRepository,
    private val memberships: BunchMembershipRepository,
    private val members: MemberRepository,
    private val follows: BunchFollowRepository,
    private val blocks: BunchBlockRepository,
    private val notices: BunchAccessNoticeService
) {
    fun current(userId: Long): BunchMembership? {
        members.findById(userId)
            .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }
            .requireCompleted()
        return memberships.findFirstByMemberIdAndStatusOrderByLastUsedAtDescIdDesc(userId)
    }

    // Called inside each read transaction; the server-selected current alone determines external access.
    fun target(userId: Long, targetId: Long, requireContent: Boolean = false): Bunch {
        positive(targetId)
        val currentId = current(userId)?.bunch?.id
        notices.requireNoticeIfPresent(userId, targetId)
        if (currentId != null) visible(currentId, targetId)
        val target = bunches.findById(targetId).orElseThrow { BusinessException(ErrorCode.BUNCH_PROFILE_UNAVAILABLE) }
        if (requireContent && !canView(target, currentId)) fail(ErrorCode.FORBIDDEN)
        return target
    }

    fun canView(target: Bunch, viewerBunchId: Long?): Boolean =
        target.visibility == Visibility.PUBLIC || target.id == viewerBunchId ||
            (viewerBunchId != null && follows.findByFollowerBunchIdAndFollowedBunchId(viewerBunchId, target.id)
                ?.status == BunchFollowStatus.APPROVED)

    fun viewer(userId: Long, viewerId: Long): BunchMembership {
        positive(viewerId)
        return memberships.findByMemberIdAndBunchIdAndStatus(userId, viewerId) ?: run {
            if (memberships.countByMemberIdAndStatus(userId) == 0L) fail(ErrorCode.CURRENT_BUNCH_REQUIRED)
            fail(ErrorCode.FORBIDDEN)
        }
    }

    fun visible(viewerId: Long, targetId: Long) {
        if (blocks.blocked(viewerId, targetId)) fail(ErrorCode.BUNCH_PROFILE_UNAVAILABLE)
    }

    private fun positive(id: Long) {
        if (id <= 0) fail(ErrorCode.INVALID_INPUT)
    }

    private fun fail(code: ErrorCode): Nothing = throw BusinessException(code)
}
