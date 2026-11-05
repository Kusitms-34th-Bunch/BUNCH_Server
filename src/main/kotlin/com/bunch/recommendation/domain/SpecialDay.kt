package com.bunch.recommendation.domain

/**
 * The days a title can be written for (the first situation of 4.2.2 rule 3). The lower case name of a constant is the
 * detail of its sentences, so `SEOLLAL` goes with the sentences of detail `seollal`.
 */
enum class SpecialDay {
    SEOLLAL,
    CHUSEOK,
    CHRISTMAS,
    NEW_YEARS_EVE,
    NEW_YEAR,
    CHILDRENS_DAY,
    HALLOWEEN,
    PEPERO,
    OTHER_HOLIDAY
}
