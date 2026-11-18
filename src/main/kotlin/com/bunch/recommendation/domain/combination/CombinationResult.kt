package com.bunch.recommendation.domain.combination

import java.time.LocalDate

/**
 * The combination of the photos of a bunch. [days] are in the order of the days. [excludedPhotoIds] are the photos left
 * out for having no date, even a borrowed one, and [movedDays] the days that got photos from another day or lost some
 * when a clock was corrected: the days to calculate again together (5-5).
 */
data class CombinationResult(
    val days: List<DayCombination>,
    val excludedPhotoIds: List<Long>,
    val movedDays: Set<LocalDate>
)
