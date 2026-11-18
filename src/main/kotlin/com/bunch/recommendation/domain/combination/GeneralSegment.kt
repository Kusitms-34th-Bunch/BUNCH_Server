package com.bunch.recommendation.domain.combination

import java.time.Instant

/**
 * The photos between two gaps of the minimum length (recommended bundle specification 7-3), with what the later rules
 * ask of them: the main activity, the part of the day it was mostly taken in, and the members who uploaded it. All
 * photos have a time, because a segment is made by the time.
 */
data class GeneralSegment(
    val photos: List<PreparedPhoto>,
    val activity: ActivityCode,
    val slot: DayPart,
    val uploaders: Set<Long>
) {
    init {
        require(photos.isNotEmpty()) {
            "a segment has at least one photo"
        }
    }

    val start: Instant
        get() = photos.first().instant!!

    val end: Instant
        get() = photos.last().instant!!
}
