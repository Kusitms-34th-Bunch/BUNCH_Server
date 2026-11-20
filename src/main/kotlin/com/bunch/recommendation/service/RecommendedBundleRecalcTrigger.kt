package com.bunch.recommendation.service

import com.bunch.bundle.dto.BundleChangedEvent
import com.bunch.photo.service.PhotoChangedEvent
import com.bunch.recommendation.domain.RecommendedBundleQueueRepository
import com.bunch.recommendation.domain.combination.CombinationSettings
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * Puts the days that a change touched in the queue of the days to calculate again (recommended bundle specification
 * 10-1, 10-2). A change puts its days to be calculated after the quiet time, and a change before that moves the time
 * later, so a day is calculated once, after the last change.
 *
 * The changes come as the events that the photo box and the bundles already give, so nothing that changes a photo or a
 * bundle has to know about the recommended bundles. The note is made in the transaction of the change, so it is there
 * exactly when the change is. A photo with no date is part of no day and is not noted. The days of a change of a date
 * are the day before and the day itself, because a night goes past midnight and the hours before the start of the day
 * belong to the day before (3-1).
 *
 * It does nothing while `reco.combination.enabled` is off, which is the default: the new recommended bundles are not
 * calculated or kept until they are switched on, because the recommended bundles that are shown are still the old ones.
 */
@Component
class RecommendedBundleRecalcTrigger(
    private val queue: RecommendedBundleQueueRepository,
    private val settings: CombinationSettings,
    private val clock: Clock,
    @Value("\${reco.combination.enabled:false}")
    private val enabled: Boolean
) {
    /** A photo was added, removed, or moved to or from a date, or an uploader left. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun noteChange(event: PhotoChangedEvent) {
        if (!enabled) return
        val date = event.capturedDate ?: return
        putDaysOf(event.bunchId, date)
    }

    /**
     * The photos of a bundle that a member makes changed: it took photos, gave them back or was deleted, and what is
     * left to recommend changes (10-1). Only these three changes are listened to, and the events of the live editing
     * are left out by the condition. The photos are not known by their day here, so every day of the bunch is noted.
     */
    @EventListener(condition = "#event.type.name() matches 'CREATED|PHOTOS|DELETED'")
    @Transactional(propagation = Propagation.MANDATORY)
    fun noteBundleChange(event: BundleChangedEvent) {
        if (!enabled) return
        queue.putAllDays(event.bunchId, dueAt())
    }

    /** The analysis of a photo is done or failed for good (10-1), so the day of the photo is calculated again. */
    fun noteAnalyzed(photoId: Long) {
        if (!enabled) return
        val change = queue.findPhotoChange(photoId) ?: return
        val date = change.capturedDate ?: return
        putDaysOf(change.bunchId, date)
    }

    private fun putDaysOf(bunchId: Long, date: LocalDate) {
        val dueAt = dueAt()
        queue.put(bunchId, date.minusDays(1), dueAt)
        queue.put(bunchId, date, dueAt)
    }

    private fun dueAt(): Instant = clock.instant().plus(settings.recalculationDebounce)
}
