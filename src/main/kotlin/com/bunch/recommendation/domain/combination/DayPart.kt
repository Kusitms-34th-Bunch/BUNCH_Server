package com.bunch.recommendation.domain.combination

/**
 * The part of the day a time falls in, on the local clock (recommended bundle specification 3-1 and 12): morning,
 * afternoon, or night, which runs past midnight. The limits are settings, see `CombinationSettings.dayPartOf`.
 */
enum class DayPart {
    MORNING,
    AFTERNOON,
    NIGHT
}
