package com.bunch.recommendation.service

import com.bunch.bunch.domain.BunchRepository
import com.bunch.recommendation.domain.RecommendationRepository
import com.bunch.recommendation.domain.RecommendedBundleQueueRepository
import com.bunch.recommendation.domain.combination.BundleCombiner
import com.bunch.recommendation.domain.combination.BundleExpiry
import com.bunch.recommendation.domain.combination.CombinationResult
import com.bunch.recommendation.domain.combination.CombinationSettings
import com.bunch.recommendation.domain.combination.DayCombination
import com.bunch.recommendation.domain.combination.RecoBundle
import com.bunch.recommendation.domain.combination.RecoPhoto
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Calculates the recommended bundles of a bunch whose days are due, and saves them (recommended bundle specification
 * 10-2, 10-3). It runs in a transaction of its own for each bunch, with the bunch locked like the photo changes are, so
 * two calculations of a bunch never run at the same time and the photos do not change while they are read.
 *
 * The photos of the whole bunch are read and calculated, not only the due days, because a day can change because of
 * another (an upload batch with a wrong clock is left out of every day, X5), and the result of the whole bunch is saved
 * ([RecommendedBundleStore]). The days that were due are taken out of the queue afterwards, except the ones whose time
 * was moved later while this ran: a change came, and it waits for its own quiet time.
 *
 * A bundle whose last photo was added more than 30 days ago is not saved (4.2.9 rule 4, [BundleExpiry]): the saved
 * bundles of the bunch are replaced by what is left, so the expired ones are deleted here too, and the daily expiry
 * ([RecommendedBundleExpiration]) deletes the ones of the bunches that are not calculated.
 *
 * Two kinds of day are put in the queue again:
 * - a day that is held, which waits for photos that are being analyzed. The analysis tells when it is done, but if it
 *   never does, the photo is read as a failed one when its waiting time is over (4-3, `AI_PENDING_TIMEOUT`) and there
 *   would be no change to tell it, so the day is calculated again at that time;
 * - a day that the saving took a photo out of (see [RecommendedBundleStoreOutcome.staleDays]), at once.
 */
@Service
class RecommendedBundleRecalculation(
    private val bunches: BunchRepository,
    private val queue: RecommendedBundleQueueRepository,
    private val inputs: RecoPhotoInputBuilder,
    private val combiner: BundleCombiner,
    private val store: RecommendedBundleStore,
    private val recommendations: RecommendationRepository,
    private val expiry: BundleExpiry,
    private val settings: CombinationSettings
) {
    /** Returns whether the bunch was calculated: it is not when it is gone or has no day due at [now]. */
    @Transactional
    fun recalculate(bunchId: Long, now: Instant): Boolean {
        if (bunches.findLocked(bunchId) == null) return false

        val due = queue.findDueDays(bunchId, now).map { it.dayKey }
        if (due.isEmpty()) return false

        val photos = inputs.build(bunchId)
        val addedAt = photos.associate { it.photoId to it.uploadedAt }
        val result = combiner.combine(photos)
        val used = recommendations.findUsedPhotoIds(bunchId).toSet()
        val outcome = store.replace(bunchId, withoutUsed(withoutExpired(result, addedAt, now), used), now, addedAt)

        queue.deleteDue(bunchId, due, now)
        waitForAnalysis(bunchId, result, photos, now)
        outcome.staleDays.forEach { queue.put(bunchId, it, now) }
        return true
    }

    // A bundle whose last photo was added more than 30 days ago is not kept (4.2.9 rule 4). The photos are still there,
    // so the calculation would make the bundle again every time; it is left out here, and it comes back when a photo
    // that was added since belongs to it. A day left with no bundle deletes the bundles it had, as a day with none
    // does.
    private fun withoutExpired(
        result: CombinationResult,
        addedAt: Map<Long, Instant>,
        now: Instant
    ): CombinationResult =
        result.copy(
            days = result.days.map { day ->
                if (day is DayCombination.Bundled) {
                    day.copy(bundles = day.bundles.filterNot { isExpired(it, addedAt, now) })
                } else {
                    day
                }
            }
        )

    // The photos of a recommended bundle that a draft was made from are not recommended again (4.1.3 rule 5). A
    // bundle of only those photos is not a recommended bundle; one with a photo that is not among them is, so the
    // photos that are left come back with the photo that was added to them (4.2.9 rule 5).
    private fun withoutUsed(result: CombinationResult, used: Set<Long>): CombinationResult {
        if (used.isEmpty()) return result

        return result.copy(
            days = result.days.map { day ->
                if (day is DayCombination.Bundled) {
                    day.copy(bundles = day.bundles.filterNot { bundle -> bundle.photos.all { it.photoId in used } })
                } else {
                    day
                }
            }
        )
    }

    private fun isExpired(bundle: RecoBundle, addedAt: Map<Long, Instant>, now: Instant): Boolean {
        val last = bundle.photos.mapNotNull { addedAt[it.photoId] }.maxOrNull() ?: return false
        return expiry.isExpired(last, now)
    }

    // A held day is calculated again when its oldest photo that is being analyzed has waited as long as it may. That
    // time is after [now], because a photo that has waited so long is read as a failed one and the day is not held.
    private fun waitForAnalysis(bunchId: Long, result: CombinationResult, photos: List<RecoPhoto>, now: Instant) {
        val uploadedAt = photos.associate { it.photoId to it.uploadedAt }
        result.days.filterIsInstance<DayCombination.Held>().forEach { held ->
            val waiting = held.pendingPhotoIds.mapNotNull { uploadedAt[it] }.minOrNull() ?: return@forEach
            queue.put(bunchId, held.dayKey, maxOf(waiting.plus(settings.aiPendingTimeout), now.plusSeconds(1)))
        }
    }
}
