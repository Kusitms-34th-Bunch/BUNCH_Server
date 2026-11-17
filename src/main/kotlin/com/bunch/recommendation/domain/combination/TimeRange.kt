package com.bunch.recommendation.domain.combination

import java.time.Instant

/** A stretch of time from [start] to [end], both on the absolute clock (recommended bundle specification 5-2). */
data class TimeRange(
    val start: Instant,
    val end: Instant
) {
    init {
        require(!end.isBefore(start)) {
            "a time range does not end before it starts"
        }
    }
}
