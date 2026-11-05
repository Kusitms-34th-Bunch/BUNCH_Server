package com.bunch.recommendation.domain

import java.time.Duration
import java.time.LocalTime

/**
 * The first-round numbers of the specification (4.2.7 rule 6, 4.2.8), meant to be tuned while the feature is used.
 * [burstMaxDifferentBits] is the largest number of differing bits of two image fingerprints still counted as alike.
 */
data class RecommendationSettings(
    val splitGap: Duration = Duration.ofHours(4),
    val minPhotosOnEachSide: Int = 3,
    val midnightFrom: LocalTime = LocalTime.of(22, 0),
    val midnightUntil: LocalTime = LocalTime.of(3, 0),
    val midnightGap: Duration = Duration.ofHours(2),
    val minPhotos: Int = 6,
    val minUploaders: Int = 2,
    val burstGap: Duration = Duration.ofSeconds(3),
    val burstMaxDifferentBits: Int = 5,
    val maxCardPhotos: Int = 10
)
