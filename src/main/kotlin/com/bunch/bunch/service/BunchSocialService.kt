package com.bunch.bunch.service

import com.bunch.bunch.domain.Bunch
import com.bunch.bunch.domain.BunchBlock
import com.bunch.bunch.domain.BunchBlockRepository
import com.bunch.bunch.domain.BunchFollow
import com.bunch.bunch.domain.BunchFollowRepository
import com.bunch.bunch.domain.BunchFollowStatus
import com.bunch.bunch.domain.BunchProfileReport
import com.bunch.bunch.domain.BunchProfileReportRepository
import com.bunch.bunch.domain.BunchReportReason
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.domain.MembershipRole
import com.bunch.bunch.domain.Visibility
import com.bunch.bunch.dto.BunchBlockItem
import com.bunch.bunch.dto.BunchFollowRequestItem
import com.bunch.bunch.dto.BunchRelationItem
import com.bunch.bunch.dto.BunchReportRequest
import com.bunch.bunch.dto.BunchReportResult
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
import com.bunch.notification.service.NotificationWriter
import jakarta.persistence.EntityManager
import java.time.Instant
import java.util.regex.Pattern
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
class BunchSocialService(
    private val bunches: BunchRepository,
    private val members: MemberRepository,
    private val follows: BunchFollowRepository,
    private val blockRepository: BunchBlockRepository,
    private val reports: BunchProfileReportRepository,
    private val access: BunchSocialAccessService,
    private val notifications: NotificationWriter,
    private val entityManager: EntityManager
) {
    @Transactional
    fun follow(userId: Long, viewerId: Long, targetId: Long): FollowResult {
        prepareMutation(userId, viewerId, targetId)
        val locked = lockPair(viewerId, targetId)
        access.viewer(userId, viewerId)
        val target = locked[targetId] ?: fail(ErrorCode.BUNCH_PROFILE_UNAVAILABLE)
        access.visible(viewerId, targetId)
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
        notifications.followCreated(locked.getValue(viewerId), target, follow, userId)
        return FollowResult.from(follow)
    }

    @Transactional
    fun cancel(userId: Long, viewerId: Long, targetId: Long, followId: Long): FollowCancellationResult {
        positive(followId)
        prepareMutation(userId, viewerId, targetId)
        lockPair(viewerId, targetId)
        access.viewer(userId, viewerId)
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
        access.viewer(userId, viewerId)
        // Read only coordinates before acquiring the pair. Loading the entity here would cache a stale state.
        val requesterId = follows.findRequesterId(requestId, viewerId)
        if (requesterId == null) {
            bunches.findLocked(viewerId)
            access.viewer(userId, viewerId)
            if (follows.existsById(requestId)) fail(ErrorCode.RESOURCE_NOT_FOUND)
            return FollowDecisionResult(requestId, false, FollowDecisionState.UNAVAILABLE)
        }

        val locked = lockPair(viewerId, requesterId)
        access.viewer(userId, viewerId)
        val current = follows.findById(requestId).orElse(null)
            ?: return FollowDecisionResult(requestId, false, FollowDecisionState.UNAVAILABLE)
        if (current.status == BunchFollowStatus.APPROVED) {
            return FollowDecisionResult(requestId, false, FollowDecisionState.APPROVED)
        }
        return when (decision) {
            FollowDecision.APPROVE -> {
                current.approve(userId, Instant.now())
                notifications.followApproved(locked.getValue(requesterId), locked.getValue(viewerId), current, userId)
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
        access.viewer(userId, viewerId)
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
        access.target(userId, targetId, viewerBunchId, requireContent = true)
        val result = if (following) {
            follows.visibleFollowing(targetId, viewerBunchId, pageable)
        } else {
            follows.visibleFollowers(targetId, viewerBunchId, pageable)
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

    @Transactional
    fun block(userId: Long, viewerId: Long, targetId: Long) {
        prepareMutation(userId, viewerId, targetId)
        val locked = lockPair(viewerId, targetId)
        requireBlockManager(userId, viewerId)
        if (locked[targetId] == null) fail(ErrorCode.BUNCH_PROFILE_UNAVAILABLE)
        if (blockRepository.findByBlockerBunchIdAndBlockedBunchId(viewerId, targetId) == null) {
            blockRepository.save(
                BunchBlock(
                    blockerBunchId = viewerId,
                    blockedBunchId = targetId,
                    createdByMemberId = userId,
                    createdAt = Instant.now()
                )
            )
        }
        follows.deleteBetween(viewerId, targetId)
    }

    @Transactional
    fun unblock(userId: Long, viewerId: Long, targetId: Long, blockId: Long) {
        positive(blockId)
        prepareMutation(userId, viewerId, targetId)
        lockPair(viewerId, targetId)
        requireBlockManager(userId, viewerId)
        val current = blockRepository.findByBlockerBunchIdAndBlockedBunchId(viewerId, targetId)
        if (current?.id == blockId) blockRepository.delete(current)
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun blocks(userId: Long, viewerId: Long, page: Int, size: Int): BunchSocialPage<BunchBlockItem> {
        completed(userId)
        access.viewer(userId, viewerId)
        val result = blockRepository.findAllByBlockerBunchId(viewerId, pageable(page, size, "createdAt"))
        val cards = cards(result.content.map { it.blockedBunchId })
        return BunchSocialPage(
            result.content.map { BunchBlockItem(it.id, cards.getValue(it.blockedBunchId), it.createdAt) },
            page,
            size,
            result.totalElements,
            result.hasNext()
        )
    }

    @Transactional
    fun report(userId: Long, viewerId: Long, request: BunchReportRequest): BunchReportResult {
        val detail = request.detail?.trim()?.takeIf { it.isNotEmpty() }
        if ((detail != null && Pattern.compile("\\X").matcher(detail).results().count() > 200) ||
            (request.reason == BunchReportReason.OTHER && detail.isNullOrBlank())
        ) fail(ErrorCode.INVALID_INPUT)
        prepareMutation(userId, viewerId, request.targetBunchId)
        val locked = lockPair(viewerId, request.targetBunchId)
        access.viewer(userId, viewerId)
        if (locked[request.targetBunchId] == null) fail(ErrorCode.BUNCH_PROFILE_UNAVAILABLE)
        reports.findByReporterBunchIdAndTargetBunchId(viewerId, request.targetBunchId)?.let {
            return BunchReportResult(it.id)
        }
        access.visible(viewerId, request.targetBunchId)
        val saved = reports.save(
            BunchProfileReport(
                reporterBunchId = viewerId,
                targetBunchId = request.targetBunchId,
                reportedByMemberId = userId,
                reason = request.reason,
                detail = detail,
                createdAt = Instant.now()
            )
        )
        return BunchReportResult(saved.id)
    }

    private fun requireBlockManager(userId: Long, viewerId: Long) {
        val membership = access.viewer(userId, viewerId)
        // Preflight may have loaded this entity before waiting for the bunch lock and a role change.
        entityManager.refresh(membership)
        if (membership.role == MembershipRole.MEMBER) fail(ErrorCode.FORBIDDEN)
    }

    private fun prepareMutation(userId: Long, viewerId: Long, targetId: Long) {
        positive(viewerId, targetId)
        if (viewerId == targetId) fail(ErrorCode.INVALID_INPUT)
        members.findLockedById(userId)?.requireCompleted() ?: fail(ErrorCode.USER_NOT_FOUND)
        access.viewer(userId, viewerId)
    }

    private fun lockPair(firstId: Long, secondId: Long): Map<Long, Bunch> =
        listOf(firstId, secondId).sorted().mapNotNull { id -> bunches.findLocked(id)?.let { id to it } }.toMap()

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
