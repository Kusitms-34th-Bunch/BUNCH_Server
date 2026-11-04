package com.bunch.recommendation.domain

import java.time.Duration
import java.time.LocalTime

/** The first-round numbers of the specification (4.2.7 rule 6), meant to be tuned while the feature is used. */
data class RecommendationSettings(
    val splitGap: Duration = Duration.ofHours(4),
    val minPhotosOnEachSide: Int = 3,
    val midnightFrom: LocalTime = LocalTime.of(22, 0),
    val midnightUntil: LocalTime = LocalTime.of(3, 0),
    val midnightGap: Duration = Duration.ofHours(2),
    val minPhotos: Int = 6,
    val minUploaders: Int = 2
)
