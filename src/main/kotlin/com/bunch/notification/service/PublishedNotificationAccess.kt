package com.bunch.notification.service

import com.bunch.bunch.service.BunchAccessNoticeService
import com.bunch.feed.domain.FeedAccessRepository
import com.bunch.feed.service.FeedAccessService
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.notification.dto.NotificationTarget
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class PublishedNotificationAccess(
    private val feedAccess: FeedAccessService,
    private val repository: FeedAccessRepository,
    private val accessNotices: BunchAccessNoticeService
) {
    fun canOpen(memberId: Long, target: NotificationTarget): Boolean {
        val currentBunchId = feedAccess.viewer(memberId).currentBunchId
        try {
            accessNotices.requireNoticeIfPresent(memberId, target.targetBunchId ?: target.bunchId)
        } catch (ex: BusinessException) {
            if (ex.errorCode != ErrorCode.BUNCH_ACCESS_UNAVAILABLE) throw ex
            return false
        }
        return visible(currentBunchId, target)
    }

    fun canDeliver(inboxBunchId: Long, target: NotificationTarget): Boolean =
        visible(inboxBunchId, target)

    private fun visible(viewerBunchId: Long?, target: NotificationTarget): Boolean {
        val bundleId = target.bundleId ?: return false
        val bundle = repository.findVisibleBundle(viewerBunchId, bundleId) ?: return false
        val ownerBunchId = target.targetBunchId ?: target.bunchId
        if (bundle.bunchId != ownerBunchId) return false
        val sourceBunchId = target.sourceBunchId ?: return true
        return sourceBunchId in repository.visibleBunchIds(viewerBunchId, listOf(sourceBunchId))
    }
}
