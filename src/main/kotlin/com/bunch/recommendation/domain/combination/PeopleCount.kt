package com.bunch.recommendation.domain.combination

/**
 * How many people are the subject of a photo, counted only, never who they are (recommended bundle specification 3-6
 * and 1.7). [P3_PLUS] is a group photo. The constants run from the fewest people to the most.
 */
enum class PeopleCount {
    P0,
    P1,
    P2,
    P3_PLUS
}
