package com.bunch.notification.service

import com.bunch.feed.domain.FeedAccessRepository
import com.bunch.feed.service.FeedAccessService
import com.bunch.notification.dto.NotificationTarget
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class PublishedNotificationAccess(
    private val feedAccess: FeedAccessService,
    private val repository: FeedAccessRepository
) {
    fun canOpen(memberId: Long, target: NotificationTarget): Boolean =
        visible(feedAccess.viewer(memberId).currentBunchId, target)

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
