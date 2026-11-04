package com.bunch.recommendation.domain

import java.time.Duration
import java.time.LocalDate

/**
 * Groups the photos of a bunch into clusters of photos taken together (4.2.7).
 *
 * Per day, the photos with a time are split at long gaps, then the photos without a time are attached to one of the
 * resulting parts. Afterwards the last part of a day and the first part of the next day are joined when the shooting
 * went on past midnight. A part joins at most once, so a cluster never covers more than two days.
 *
 * The rule "no member uploaded on both sides of the cut" (4.2.7 detail 2) waits for the planning team's answer and
 * is not applied; [split] is the place for it.
 */
class PhotoClusterer(private val settings: RecommendationSettings = RecommendationSettings()) {
    fun cluster(samples: List<PhotoSample>): List<PhotoCluster> {
        val parts = samples.groupBy { it.capturedDate }.toSortedMap().values.map(::partsOfDay)
        return join(parts).map(::toCluster)
    }

    private class Part(
        val date: LocalDate,
        val timed: List<PhotoSample>,
        val untimed: List<PhotoSample>
    )

    private fun partsOfDay(photos: List<PhotoSample>): List<Part> {
        val date = photos.first().capturedDate
        val timed = photos.filter { it.moment != null }.sortedWith(compareBy({ it.moment }, { it.photoId }))
        val untimed = photos.filter { it.moment == null }.sortedBy { it.photoId }
        if (timed.isEmpty()) return listOf(Part(date, emptyList(), untimed))

        val parts = split(timed).map { Part(date, it, emptyList()) }
        if (untimed.isEmpty()) return parts
        if (parts.size == 1) return listOf(Part(date, timed, untimed))

        val attached = untimed.groupBy { choose(it, parts) }
        return parts.mapIndexed { index, part -> Part(date, part.timed, attached[index].orEmpty()) }
    }

    // Cuts at the longest gap that leaves enough photos on both sides, then looks at each side again.
    private fun split(photos: List<PhotoSample>): List<List<PhotoSample>> {
        val side = settings.minPhotosOnEachSide.coerceAtLeast(1)
        val cut = (side..photos.size - side)
            .map { it to Duration.between(photos[it - 1].moment, photos[it].moment) }
            .filter { it.second >= settings.splitGap }
            .maxByOrNull { it.second }
            ?: return listOf(photos)
        return split(photos.subList(0, cut.first)) + split(photos.subList(cut.first, photos.size))
    }

    // Decides the part of a photo without a time: its upload batch, then its uploader, then the biggest part.
    private fun choose(photo: PhotoSample, parts: List<Part>): Int {
        var candidates = parts.indices.toList()

        val batchMates = candidates.associateWith { index ->
            parts[index].timed.count { it.batchIds.any(photo.batchIds::contains) }
        }
        val mostMates = batchMates.values.max()
        if (mostMates > 0) {
            candidates = candidates.filter { batchMates[it] == mostMates }
            if (candidates.size == 1) return candidates.first()
        }

        val byUploader = photo.uploaderIds
            .mapNotNull { uploader ->
                parts.indices.filter { index -> parts[index].timed.any { uploader in it.uploaderIds } }.singleOrNull()
            }
            .distinct()
            .filter { it in candidates }
        if (byUploader.size == 1) return byUploader.first()

        return candidates.maxWith(compareBy<Int> { parts[it].timed.size }.thenByDescending { it })
    }

    private fun join(days: List<List<Part>>): List<List<Part>> {
        val groups = mutableListOf<List<Part>>()
        for (day in days) {
            day.forEachIndexed { index, part ->
                val last = groups.lastOrNull()
                if (index == 0 && last != null && last.size == 1 && continues(last.last(), part)) {
                    groups[groups.lastIndex] = last + part
                } else {
                    groups += listOf(part)
                }
            }
        }
        return groups
    }

    // True when the shooting of `before` went on past midnight into `after`.
    private fun continues(before: Part, after: Part): Boolean {
        val end = before.timed.lastOrNull()?.moment ?: return false
        val start = after.timed.firstOrNull()?.moment ?: return false
        return after.date == before.date.plusDays(1) &&
            end.toLocalTime() >= settings.midnightFrom &&
            start.toLocalTime() < settings.midnightUntil &&
            Duration.between(end, start) <= settings.midnightGap
    }

    private fun toCluster(parts: List<Part>): PhotoCluster {
        val photos = parts.flatMap { it.timed + it.untimed }
        val uploaders = photos.flatMap { it.uploaderIds }.toSet()
        return PhotoCluster(
            photoIds = photos.map { it.photoId },
            startDate = parts.first().date,
            endDate = parts.getOrNull(1)?.date,
            uploaderIds = uploaders,
            qualifies = photos.size >= settings.minPhotos && uploaders.size >= settings.minUploaders
        )
    }
}
