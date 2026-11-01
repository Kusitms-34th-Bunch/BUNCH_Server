package com.bunch.bunch.service

import com.bunch.bunch.domain.Bunch
import com.bunch.bunch.domain.BunchFollow
import com.bunch.bunch.domain.BunchFollowRepository
import com.bunch.bunch.domain.BunchFollowStatus
import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.domain.Visibility
import com.bunch.bunch.dto.BunchFollowRequestItem
import com.bunch.bunch.dto.BunchRelationItem
import com.bunch.bunch.dto.BunchSocialCard
import com.bunch.bunch.dto.BunchSocialPage
import com.bunch.bunch.dto.FollowCancellationResult
import com.bunch.bunch.dto.FollowDecision
import com.bunch.bunch.dto.FollowDecisionResult
import com.bunch.bunch.dto.FollowDecisionState
import com.bunch.bunch.dto.FollowResult
import com.bunch.bunch.dto.FollowState
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.member.domain.MemberRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class BunchSocialService(
    private val bunches: BunchRepository,
    private val memberships: BunchMembershipRepository,
    private val members: MemberRepository,
    private val follows: BunchFollowRepository
) {
    @Transactional
    fun follow(userId: Long, viewerId: Long, targetId: Long): FollowResult {
        prepareMutation(userId, viewerId, targetId)
        val locked = lockPair(viewerId, targetId)
        requireViewer(userId, viewerId)
        val target = locked[targetId] ?: fail(ErrorCode.BUNCH_PROFILE_UNAVAILABLE)
        follows.findByFollowerBunchIdAndFollowedBunchId(viewerId, targetId)?.let { return FollowResult.from(it) }

        val now = Instant.now()
        val approved = target.visibility == Visibility.PUBLIC
        val follow = follows.save(
            BunchFollow(
                followerBunchId = viewerId,
                followedBunchId = targetId,
                status = if (approved) BunchFollowStatus.APPROVED else BunchFollowStatus.PENDING,
                createdByMemberId = userId,
                createdAt = now,
                approvedAt = if (approved) now else null
            )
        )
        return FollowResult.from(follow)
    }

    @Transactional
    fun cancel(userId: Long, viewerId: Long, targetId: Long, followId: Long): FollowCancellationResult {
        positive(followId)
        prepareMutation(userId, viewerId, targetId)
        lockPair(viewerId, targetId)
        requireViewer(userId, viewerId)
        val current = follows.findByFollowerBunchIdAndFollowedBunchId(viewerId, targetId)
        if (current?.id != followId) {
            val result = FollowResult.from(current)
            return FollowCancellationResult(result.followState, result.followId, FollowState.NONE)
        }

        val cancelled = FollowResult.from(current).followState
        follows.delete(current)
        return FollowCancellationResult(FollowState.NONE, null, cancelled)
    }

    @Transactional
    fun decide(userId: Long, viewerId: Long, requestId: Long, decision: FollowDecision): FollowDecisionResult {
        positive(viewerId, requestId)
        members.findLockedById(userId)?.requireCompleted() ?: fail(ErrorCode.USER_NOT_FOUND)
        requireViewer(userId, viewerId)
        // Read only coordinates before acquiring the pair. Loading the entity here would cache a stale state.
        val requesterId = follows.findRequesterId(requestId, viewerId)
        if (requesterId == null) {
            bunches.findLocked(viewerId)
            requireViewer(userId, viewerId)
            if (follows.existsById(requestId)) fail(ErrorCode.RESOURCE_NOT_FOUND)
            return FollowDecisionResult(requestId, false, FollowDecisionState.UNAVAILABLE)
        }

        lockPair(viewerId, requesterId)
        requireViewer(userId, viewerId)
        val current = follows.findById(requestId).orElse(null)
            ?: return FollowDecisionResult(requestId, false, FollowDecisionState.UNAVAILABLE)
        if (current.status == BunchFollowStatus.APPROVED) {
            return FollowDecisionResult(requestId, false, FollowDecisionState.APPROVED)
        }
        return when (decision) {
            FollowDecision.APPROVE -> {
                current.approve(userId, Instant.now())
                FollowDecisionResult(requestId, true, FollowDecisionState.APPROVED)
            }
            FollowDecision.REJECT -> {
                follows.delete(current)
                FollowDecisionResult(requestId, true, FollowDecisionState.REJECTED)
            }
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun requests(userId: Long, viewerId: Long, page: Int, size: Int): BunchSocialPage<BunchFollowRequestItem> {
        positive(viewerId)
        completed(userId)
        requireViewer(userId, viewerId)
        val result = follows.findAllByFollowedBunchIdAndStatus(
            viewerId,
            BunchFollowStatus.PENDING,
            pageable(page, size, "createdAt")
        )
        val cards = cards(result.content.map { it.followerBunchId })
        return BunchSocialPage(
            result.content.map { BunchFollowRequestItem(it.id, cards.getValue(it.followerBunchId), it.createdAt) },
            page,
            size,
            result.totalElements,
            result.hasNext()
        )
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun relations(
        userId: Long,
        targetId: Long,
        viewerBunchId: Long?,
        following: Boolean,
        page: Int,
        size: Int
    ): BunchSocialPage<BunchRelationItem> {
        positive(targetId)
        val pageable = pageable(page, size, "approvedAt")
        completed(userId)
        if (viewerBunchId == null) {
            if (memberships.countByMemberIdAndStatus(userId) != 0L) fail(ErrorCode.CURRENT_BUNCH_REQUIRED)
        } else {
            positive(viewerBunchId)
            requireViewer(userId, viewerBunchId)
        }
        val target = bunches.findById(targetId).orElseThrow { BusinessException(ErrorCode.BUNCH_PROFILE_UNAVAILABLE) }
        if (target.visibility != Visibility.PUBLIC && viewerBunchId != targetId) {
            val relation = viewerBunchId?.let { follows.findByFollowerBunchIdAndFollowedBunchId(it, targetId) }
            if (relation?.status != BunchFollowStatus.APPROVED) fail(ErrorCode.FORBIDDEN)
        }
        val result = if (following) {
            follows.findAllByFollowerBunchIdAndStatus(targetId, BunchFollowStatus.APPROVED, pageable)
        } else {
            follows.findAllByFollowedBunchIdAndStatus(targetId, BunchFollowStatus.APPROVED, pageable)
        }
        val ids = result.content.map { if (following) it.followedBunchId else it.followerBunchId }
        val cards = cards(ids)
        val viewerRelations = if (viewerBunchId != null && ids.isNotEmpty()) {
            follows.findAllByFollowerBunchIdAndFollowedBunchIdIn(viewerBunchId, ids).associateBy { it.followedBunchId }
        } else {
            emptyMap()
        }
        val items = result.content.map { relation ->
            val id = if (following) relation.followedBunchId else relation.followerBunchId
            val viewerState = FollowResult.from(viewerRelations[id])
            BunchRelationItem(cards.getValue(id), viewerState.followState, viewerState.followId, relation.approvedAt!!)
        }
        return BunchSocialPage(items, page, size, result.totalElements, result.hasNext())
    }

    private fun prepareMutation(userId: Long, viewerId: Long, targetId: Long) {
        positive(viewerId, targetId)
        if (viewerId == targetId) fail(ErrorCode.INVALID_INPUT)
        members.findLockedById(userId)?.requireCompleted() ?: fail(ErrorCode.USER_NOT_FOUND)
        requireViewer(userId, viewerId)
    }

    private fun lockPair(firstId: Long, secondId: Long): Map<Long, Bunch> =
        listOf(firstId, secondId).sorted().mapNotNull { id -> bunches.findLocked(id)?.let { id to it } }.toMap()

    private fun requireViewer(userId: Long, viewerId: Long) {
        if (memberships.findByMemberIdAndBunchIdAndStatus(userId, viewerId) == null) {
            if (memberships.countByMemberIdAndStatus(userId) == 0L) fail(ErrorCode.CURRENT_BUNCH_REQUIRED)
            fail(ErrorCode.FORBIDDEN)
        }
    }

    private fun completed(userId: Long) = members.findById(userId)
        .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }
        .requireCompleted()

    private fun cards(ids: Collection<Long>): Map<Long, BunchSocialCard> =
        if (ids.isEmpty()) emptyMap() else follows.findCardBunches(ids).associate { it.id to BunchSocialCard.from(it) }

    private fun pageable(page: Int, size: Int, time: String): PageRequest {
        if (page < 0 || size !in 1..50 || page.toLong() * size > Int.MAX_VALUE) fail(ErrorCode.INVALID_INPUT)
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, time, "id"))
    }

    private fun positive(vararg ids: Long) {
        if (ids.any { it <= 0 }) fail(ErrorCode.INVALID_INPUT)
    }

    private fun fail(code: ErrorCode): Nothing = throw BusinessException(code)
}
