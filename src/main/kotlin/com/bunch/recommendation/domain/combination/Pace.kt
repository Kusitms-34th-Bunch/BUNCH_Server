package com.bunch.recommendation.domain.combination

/**
 * How long a gap may be before photos of one event or one activity count as two (recommended bundle specification 3-5).
 * The gap itself is a setting, see `CombinationSettings.continuityOf`. The constants run from the shortest to the
 * longest, so [longer] is the one with the bigger ordinal.
 */
enum class Pace {
    SHORT,
    LONG,
    DAY;

    /** The longer of two paces: when two are looked at together, the longer one rules (3-5). */
    fun longer(other: Pace): Pace = if (other.ordinal > ordinal) other else this
}
