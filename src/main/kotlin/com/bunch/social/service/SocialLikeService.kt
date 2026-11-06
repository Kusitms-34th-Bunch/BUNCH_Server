package com.bunch.social.service

import com.bunch.bunch.domain.BunchRepository
import com.bunch.bundle.domain.BundleRepository
import com.bunch.feed.service.FeedAccessService
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.member.domain.MemberRepository
import com.bunch.notification.service.NotificationWriter
import com.bunch.social.domain.SocialBundleLike
import com.bunch.social.domain.SocialBundleLikeRepository
import com.bunch.social.dto.SocialLikeResponse
import com.bunch.social.dto.SocialLikeSummary
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class SocialLikeService(
    private val likes: SocialBundleLikeRepository,
    private val access: FeedAccessService,
    private val members: MemberRepository,
    private val bunches: BunchRepository,
    private val bundles: BundleRepository,
    private val notifications: NotificationWriter,
    private val entityManager: EntityManager,
    private val clock: Clock
) {
    @Transactional
    fun like(userId: Long, bundleId: Long): SocialLikeResponse = change(userId, bundleId, true)

    @Transactional
    fun unlike(userId: Long, bundleId: Long): SocialLikeResponse = change(userId, bundleId, false)

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun summary(userId: Long, bundleId: Long): SocialLikeResponse {
        access.readPublishedBundle(userId, bundleId)
        val currentBunchId = access.viewer(userId).currentBunchId
        return response(bundleId, currentBunchId, counts(currentBunchId, listOf(bundleId)).getValue(bundleId))
    }

    // C/B call once for their bounded candidate set. Inaccessible targets are omitted, never zero-filled.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun summaries(userId: Long, bundleIds: Collection<Long>): Map<Long, SocialLikeSummary> {
        val visible = access.visibleBundleIds(userId, bundleIds, forFeed = false)
        if (visible.isEmpty()) return emptyMap()
        return counts(access.viewer(userId).currentBunchId, visible)
    }

    private fun change(userId: Long, bundleId: Long, active: Boolean): SocialLikeResponse {
        if (bundleId <= 0) fail(ErrorCode.INVALID_INPUT)
        members.findLockedById(userId)?.requireCompleted() ?: fail(ErrorCode.USER_NOT_FOUND)
        val selectedActor = access.requireActor(userId)
        val selectedBunchId = selectedActor.bunch.id
        // Read coordinates only before locking; do not cache an unlocked bundle entity.
        val targetBunchId = bundles.findBunchId(bundleId) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
        listOf(selectedBunchId, targetBunchId).distinct().sorted().forEach { id ->
            val bunch = bunches.findLocked(id) ?: fail(ErrorCode.BUNCH_PROFILE_UNAVAILABLE)
            entityManager.refresh(bunch)
        }
        bundles.findLocked(bundleId) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
        // Membership may have changed while waiting for the bunch locks.
        entityManager.refresh(selectedActor)
        val interaction = access.requireInteraction(userId, bundleId)
        if (interaction.actor.bunch.id != selectedBunchId) fail(ErrorCode.FORBIDDEN)

        val previous = likes.findByBunchIdAndBundleId(selectedBunchId, bundleId)
        val now = clock.instant()
        if (previous == null && active) {
            likes.save(SocialBundleLike(bunchId = selectedBunchId, bundleId = bundleId, firstLikedAt = now))
            notifications.bundleLiked(interaction.bundle, interaction.actor.bunch, userId)
        } else if (previous != null && previous.active != active) {
            previous.active = active
            previous.updatedAt = now
        }
        return response(bundleId, selectedBunchId, counts(selectedBunchId, listOf(bundleId)).getValue(bundleId))
    }

    private fun counts(currentBunchId: Long?, bundleIds: Collection<Long>): Map<Long, SocialLikeSummary> {
        val rows = likes.counts(currentBunchId, bundleIds).associateBy { it.bundleId }
        return bundleIds.associateWith { id ->
            rows[id]?.let { SocialLikeSummary(it.likeCount, it.currentBunchLikes > 0) } ?: SocialLikeSummary()
        }
    }

    private fun response(bundleId: Long, currentBunchId: Long?, summary: SocialLikeSummary) =
        SocialLikeResponse(bundleId, currentBunchId, summary.likedByCurrentBunch, summary.likeCount)

    private fun fail(code: ErrorCode): Nothing = throw BusinessException(code)
}
