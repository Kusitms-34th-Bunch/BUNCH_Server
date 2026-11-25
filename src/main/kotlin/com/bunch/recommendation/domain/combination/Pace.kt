package com.bunch.recommendation.domain.combination

/**
 * How long an event or an activity lasts, from the shortest to the longest, so [longer] is the one with the bigger
 * ordinal. The codes of the events and the activities carry it; the cutting of the general bundles does not use it any
 * more (it cuts by the gaps of the settings alone).
 */
enum class Pace {
    SHORT,
    LONG,
    DAY;

    /** The longer of two paces: when two are looked at together, the longer one rules (3-5). */
    fun longer(other: Pace): Pace = if (other.ordinal > ordinal) other else this
}
