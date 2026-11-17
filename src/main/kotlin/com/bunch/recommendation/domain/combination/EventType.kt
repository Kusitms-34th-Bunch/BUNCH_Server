package com.bunch.recommendation.domain.combination

/**
 * The special events a bundle can be named after (recommended bundle specification 3-3) and the [pace] of each (3-5).
 */
enum class EventType(val pace: Pace) {
    BIRTHDAY(Pace.SHORT),
    GRADUATION(Pace.SHORT),
    YEAR_END(Pace.SHORT),
    WEDDING(Pace.LONG),
    CONCERT(Pace.LONG),
    SPORTS_VIEWING(Pace.LONG),
    COMPETITION(Pace.LONG),
    TRAVEL(Pace.DAY),
    CAMPING(Pace.DAY),
    HOLIDAY(Pace.DAY),
    THEME_PARK(Pace.DAY)
}
