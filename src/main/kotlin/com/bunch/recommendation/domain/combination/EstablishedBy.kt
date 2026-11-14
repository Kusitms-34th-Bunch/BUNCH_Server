package com.bunch.recommendation.domain.combination

/**
 * What made an event stand (recommended bundle specification 4-5 and 6-1). Only [STRONG] gives the event its own name
 * in the title; the other two give a name of the day (9-1).
 */
enum class EstablishedBy {
    STRONG,
    SUPPORT,
    DATE_BOOST
}
