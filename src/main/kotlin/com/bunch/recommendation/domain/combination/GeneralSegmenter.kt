package com.bunch.recommendation.domain.combination

import java.time.Duration

/**
 * Cuts a chunk into segments at the gaps of [CombinationSettings.minBoundaryGap] or more (recommended bundle
 * specification 7-3). The gaps are measured between the photos that have a time, whether they are usable or not
 * (5-1). Cutting here does not decide to separate anything: the later rules decide, and only at these gaps.
 */
class GeneralSegmenter(
    private val settings: CombinationSettings = CombinationSettings(),
    private val activities: GeneralActivities = GeneralActivities(settings)
) {
    fun segment(photos: List<PreparedPhoto>, counter: GeneralCounter): List<GeneralSegment> {
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
            GeneralSegment(
                photos = it,
                activity = activities.representative(it),
                slot = activities.slotOf(it),
                uploaders = counter.uploaders(it)
            )
        }
    }
}
