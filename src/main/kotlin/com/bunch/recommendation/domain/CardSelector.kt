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
 * cluster shows. The distance is also taken from the photos the same member already has, so the photos of one member
 * do not crowd into a part of the day that other members already cover (rule 2). A photo uploaded together is taken
 * once and counts for the one of its uploaders who has the fewest photos in the cluster, the one who uploaded it
 * first when they have as many (rule 3).
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
        val counts = photos.flatMap { it.uploaderIds }.groupingBy { it }.eachCount()
        val members = counts.keys.sortedWith(compareBy({ member -> counts.getValue(member) }, { member -> member }))
        val owners = photos.associate { photo -> photo.photoId to ownerOf(photo, counts) }
        for (member in members) {
            if (chosen.size >= settings.maxCardPhotos) break
            val own = notChosen(photos, chosen).filter { owners[it.photoId] == member }
            farthest(own, chosen, photos)?.let { chosen += it }
        }
        while (chosen.size < settings.maxCardPhotos) {
            val next = farthest(notChosen(photos, chosen), chosen, photos) ?: break
            chosen += next
        }
        return chosen
    }

    // The member a photo counts for (4.2.8 rule 3). A photo several members uploaded counts for the one of them with
    // the fewest photos in the cluster, so a member with few photos does not lose a place to it, and for the one who
    // uploaded it first when they have as many. The uploaders are kept in the order they uploaded in.
    private fun ownerOf(photo: PhotoSample, counts: Map<Long, Int>): Long =
        photo.uploaderIds.minBy { member -> counts.getValue(member) }

    private fun notChosen(photos: List<PhotoSample>, chosen: List<PhotoSample>): List<PhotoSample> =
        photos.filter { photo -> chosen.none { it === photo } }

    // Takes the candidate that is farthest in time from the chosen photos and from the photos its own uploaders already
    // have, or the one nearest to the middle of the cluster when nothing with a time is chosen yet. A candidate without
    // a time is taken only when no candidate has a time.
    private fun farthest(
        candidates: List<PhotoSample>,
        chosen: List<PhotoSample>,
        cluster: List<PhotoSample>
    ): PhotoSample? {
        val timed = candidates.filter { it.moment != null }
        if (timed.isEmpty()) return candidates.minByOrNull { it.photoId }

        val middle = middleOf(cluster)
        if (chosen.none { it.moment != null } && middle != null) {
            return timed.minWith(
                compareBy({ photo -> secondsBetween(photo.moment, middle) }, { photo -> photo.photoId })
            )
        }
        val span = spanSeconds(cluster)
        return timed.maxWith(
            compareBy<PhotoSample> { photo -> distanceToChosen(photo, chosen, span) }
                .thenByDescending { it.photoId }
        )
    }

    // The distance to all chosen photos times the distance to the chosen photos of the same uploaders. A photo at the
    // time of a chosen photo scores zero, and so does one right next to a photo of its own uploader. A member with no
    // chosen photo yet counts as the whole span of the cluster, the same for every candidate of that member.
    private fun distanceToChosen(photo: PhotoSample, chosen: List<PhotoSample>, span: Long): Long {
        val overall = nearestSeconds(photo, chosen) ?: 0L
        val ownBest = photo.uploaderIds.maxOf { member ->
            nearestSeconds(photo, chosen.filter { member in it.uploaderIds }) ?: span
        }
        return overall * ownBest
    }

    private fun nearestSeconds(photo: PhotoSample, others: List<PhotoSample>): Long? =
        others.mapNotNull { it.moment }.minOfOrNull { secondsBetween(photo.moment, it) }

    private fun spanSeconds(cluster: List<PhotoSample>): Long {
        val moments = cluster.mapNotNull { it.moment }
        val first = moments.minOrNull() ?: return 0L
        val last = moments.maxOrNull() ?: return 0L
        return Duration.between(first, last).seconds
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
