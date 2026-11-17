package com.bunch.recommendation.domain.combination

/** How much of the shooting time of a photo is known (recommended bundle specification 3-6). */
enum class TimeState {
    /** The date and the time. */
    FULL,

    /** The date only. */
    DATE_ONLY,

    /** Nothing. */
    UNKNOWN
}
