package com.bunch.recommendation.domain.combination

/**
 * What the members were doing in a photo (recommended bundle specification 3-4) and the [pace] of it (3-5).
 * [UNKNOWN] has no pace: a photo of an unknown activity is never the reason to keep or to cut a group.
 * The constants are in the order of the specification table, because a tie between two activities is broken by
 * that order (7-3).
 */
enum class ActivityCode(val pace: Pace?) {
    MEAL(Pace.SHORT),
    CAFE(Pace.SHORT),
    DRINKS(Pace.SHORT),
    CELEBRATION(Pace.SHORT),
    STUDY_WORK(Pace.SHORT),
    HOME(Pace.SHORT),
    WALK(Pace.SHORT),
    SHOW_EXHIBITION(Pace.LONG),
    SPORTS(Pace.LONG),
    SHOPPING(Pace.LONG),
    OTHER(Pace.LONG),
    SIGHTSEEING(Pace.DAY),
    OUTDOOR(Pace.DAY),
    TRANSIT(Pace.DAY),
    UNKNOWN(null)
}
