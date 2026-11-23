package com.bunch.recommendation.domain.combination

import java.time.Duration

/**
 * Cuts a chunk into segments at the gaps of [CombinationSettings.minBoundaryGap] or more (recommended bundle
 * specification 3-1). The gaps are measured between the photos that have a time, whether they are usable or not.
 * Cutting here does not decide to separate anything: the later rules decide, and only at these gaps.
 */
class GeneralSegmenter(
    private val settings: CombinationSettings = CombinationSettings()
) {
    fun segment(photos: List<PreparedPhoto>): List<GeneralSegment> {
        val runs = mutableListOf<MutableList<PreparedPhoto>>()
        for (photo in photos) {
            val previous = runs.lastOrNull()?.last()
            val gap = previous?.let { Duration.between(it.instant!!, photo.instant!!) }
            if (gap != null && gap < settings.minBoundaryGap) {
                runs.last() += photo
            } else {
                runs += mutableListOf(photo)
            }
        }
        return runs.map {
            GeneralSegment(photos = it, slot = settings.mainDayPartOf(it))
        }
    }
}
