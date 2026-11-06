package com.bunch.feed.service

import com.bunch.bunch.domain.BunchMembership
import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.service.BunchAccessNoticeService
import com.bunch.bunch.service.BunchSocialAccessService
import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.domain.BundleRepository
import com.bunch.feed.domain.FeedAccessRepository
import com.bunch.feed.dto.FeedBundleInteraction
import com.bunch.feed.dto.FeedViewer
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class FeedAccessService(
    private val socialAccess: BunchSocialAccessService,
    private val memberships: BunchMembershipRepository,
    private val bunches: BunchRepository,
    private val notices: BunchAccessNoticeService,
    private val bundles: BundleRepository,
    private val access: FeedAccessRepository
) {
    fun viewer(userId: Long): FeedViewer {
        val current = socialAccess.current(userId)
        return FeedViewer(userId, current?.bunch?.id, memberships.findActiveBunchIds(userId).toSet())
    }

    fun requireActor(userId: Long): BunchMembership =
        socialAccess.current(userId) ?: fail(ErrorCode.CURRENT_BUNCH_REQUIRED)

    fun readPublishedBundle(userId: Long, bundleId: Long): Bundle {
        val currentBunchId = socialAccess.current(userId)?.bunch?.id
        return readPublishedBundle(userId, bundleId, currentBunchId)
    }

    fun requireInteraction(userId: Long, bundleId: Long): FeedBundleInteraction {
        val actor = requireActor(userId)
        val bundle = readPublishedBundle(userId, bundleId, actor.bunch.id)
        return FeedBundleInteraction(bundle, actor)
    }

    // A successful first report must not prevent the permitted second report of the same bundle.
    fun requireBundleReportTarget(userId: Long, bundleId: Long): FeedBundleInteraction {
        val actor = requireActor(userId)
        positive(bundleId)
        val bundle = bundles.findById(bundleId).orElseThrow { BusinessException(ErrorCode.RESOURCE_NOT_FOUND) }
        requireTarget(userId, bundle.bunchId, actor.bunch.id)
        val reportable = access.findReportableBundle(actor.bunch.id, bundleId) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
        return FeedBundleInteraction(reportable, actor)
    }

    fun publishedBundles(userId: Long, bunchId: Long, pageable: Pageable): Page<Bundle> {
        val currentBunchId = socialAccess.current(userId)?.bunch?.id
        requireTarget(userId, bunchId, currentBunchId)
        return access.findPublishedBundles(currentBunchId, bunchId, pageable)
    }

    // Candidate-set validation, not a substitute for predicates before LIMIT in a feed query.
    fun visibleBundleIds(userId: Long, bundleIds: Collection<Long>, forFeed: Boolean = true): Set<Long> {
        val currentBunchId = socialAccess.current(userId)?.bunch?.id
        validateBatch(bundleIds)
        if (bundleIds.isEmpty()) return emptySet()
        return access.visibleBundleIds(userId, currentBunchId, bundleIds, forFeed)
    }

    // Attribution/count filter: private acting bunches need not be followed to leave visible reactions.
    fun visibleBunchIds(userId: Long, bunchIds: Collection<Long>): Set<Long> {
        val currentBunchId = socialAccess.current(userId)?.bunch?.id
        validateBatch(bunchIds)
        if (bunchIds.isEmpty()) return emptySet()
        return access.visibleBunchIds(currentBunchId, bunchIds)
    }

    private fun validateBatch(ids: Collection<Long>) {
        if (ids.size > 500) fail(ErrorCode.INVALID_INPUT)
        ids.forEach(::positive)
    }

    private fun readPublishedBundle(userId: Long, bundleId: Long, currentBunchId: Long?): Bundle {
        positive(bundleId)
        val bundle = bundles.findById(bundleId).orElseThrow { BusinessException(ErrorCode.RESOURCE_NOT_FOUND) }
        requireTarget(userId, bundle.bunchId, currentBunchId)
        return access.findVisibleBundle(currentBunchId, bundle.id) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
    }

    private fun requireTarget(userId: Long, bunchId: Long, currentBunchId: Long?) {
        positive(bunchId)
        notices.requireNoticeIfPresent(userId, bunchId)
        if (currentBunchId != null) socialAccess.visible(currentBunchId, bunchId)
        val bunch = bunches.findById(bunchId).orElseThrow { BusinessException(ErrorCode.BUNCH_PROFILE_UNAVAILABLE) }
        if (!socialAccess.canView(bunch, currentBunchId)) fail(ErrorCode.FORBIDDEN)
    }

    private fun positive(id: Long) {
        if (id <= 0) fail(ErrorCode.INVALID_INPUT)
    }

    private fun fail(code: ErrorCode): Nothing = throw BusinessException(code)
}
