package com.bunch.recommendation.domain

import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.abs

/**
 * Picks the photos of a recommended bundle card out of the photos of one cluster (4.2.8).
 *
 * Burst photos are reduced first and the recommendation condition is judged on what is left. Up to the largest number
 * of cards everything is taken. Above it, every member gets a photo, the members with fewer photos first, and the
 * remaining places go to the photos that are farthest in time from the ones already taken, so the whole time of the
 * cluster shows. A photo uploaded together is taken once and counts for the member whose turn it was.
 */
class CardSelector(
    private val settings: RecommendationSettings = RecommendationSettings(),
    private val burstReducer: BurstReducer = BurstReducer(settings)
) {
    fun select(photos: List<PhotoSample>): CardSelection {
        val remaining = burstReducer.reduce(photos)
        val uploaders = remaining.flatMap { it.uploaderIds }.toSet()
        val recommended = remaining.size >= settings.minPhotos && uploaders.size >= settings.minUploaders
        if (!recommended) return CardSelection(emptyList(), remaining.size, uploaders, false)

        val chosen = if (remaining.size <= settings.maxCardPhotos) remaining else pick(remaining)
        return CardSelection(
            photoIds = inCardOrder(chosen).map { it.photoId },
            photoCount = remaining.size,
            uploaderIds = chosen.flatMap { it.uploaderIds }.toSet(),
            recommended = true
        )
    }

    private fun pick(photos: List<PhotoSample>): List<PhotoSample> {
        val chosen = mutableListOf<PhotoSample>()
        val members = photos.flatMap { it.uploaderIds }.distinct()
            .sortedWith(compareBy({ member -> photos.count { member in it.uploaderIds } }, { member -> member }))
        for (member in members) {
            if (chosen.size >= settings.maxCardPhotos) break
            val own = notChosen(photos, chosen).filter { member in it.uploaderIds }
            farthest(own, chosen, photos)?.let { chosen += it }
        }
        while (chosen.size < settings.maxCardPhotos) {
            val next = farthest(notChosen(photos, chosen), chosen, photos) ?: break
            chosen += next
        }
        return chosen
    }

    private fun notChosen(photos: List<PhotoSample>, chosen: List<PhotoSample>): List<PhotoSample> =
        photos.filter { photo -> chosen.none { it === photo } }

    // Takes the candidate farthest in time from the chosen photos, or the one nearest to the middle of the cluster when
    // nothing with a time is chosen yet. A candidate without a time is taken only when no candidate has a time.
    private fun farthest(
        candidates: List<PhotoSample>,
        chosen: List<PhotoSample>,
        cluster: List<PhotoSample>
    ): PhotoSample? {
        val timed = candidates.filter { it.moment != null }
        if (timed.isEmpty()) return candidates.minByOrNull { it.photoId }

        val chosenMoments = chosen.mapNotNull { it.moment }
        val middle = middleOf(cluster)
        if (chosenMoments.isEmpty() && middle != null) {
            return timed.minWith(
                compareBy({ photo -> secondsBetween(photo.moment, middle) }, { photo -> photo.photoId })
            )
        }
        return timed.maxWith(
            compareBy<PhotoSample> { photo -> chosenMoments.minOfOrNull { secondsBetween(photo.moment, it) } ?: 0L }
                .thenByDescending { it.photoId }
        )
    }

    private fun middleOf(cluster: List<PhotoSample>): LocalDateTime? {
        val moments = cluster.mapNotNull { it.moment }
        val first = moments.minOrNull() ?: return null
        val last = moments.maxOrNull() ?: return null
        return first.plus(Duration.between(first, last).dividedBy(2))
    }

    private fun secondsBetween(from: LocalDateTime?, to: LocalDateTime?): Long =
        if (from == null || to == null) Long.MAX_VALUE else abs(Duration.between(from, to).seconds)

    // The order of a recommended bundle: by date, the photos with a time first, then the ones without in upload order.
    private fun inCardOrder(photos: List<PhotoSample>): List<PhotoSample> = photos.sortedWith(
        compareBy({ it.capturedDate }, { it.capturedTime == null }, { it.capturedTime }, { it.photoId })
    )
}
