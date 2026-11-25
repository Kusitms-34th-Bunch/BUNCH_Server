package com.bunch.recommendation.domain.combination

import java.time.Instant

/**
 * The photos between two gaps of the minimum length (recommended bundle specification 3-1), with what the later rules
 * ask of them: the part of the day it was mostly taken in (the part of the segment). All photos have a time, because a
 * segment is made by the time.
 */
data class GeneralSegment(
    val photos: List<PreparedPhoto>,
    val slot: DayPart
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
