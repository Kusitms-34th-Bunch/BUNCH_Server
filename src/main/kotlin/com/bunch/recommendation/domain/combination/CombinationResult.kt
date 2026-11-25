package com.bunch.recommendation.domain.combination

/**
 * The combination of the photos of a bunch. [days] are in the order of the days. [excludedPhotoIds] are the photos left
 * out of the calculation: the ones without a date and the ones of an upload batch taken with a wrong clock (3-1).
 */
data class CombinationResult(
    val days: List<DayCombination>,
    val excludedPhotoIds: List<Long>
)
