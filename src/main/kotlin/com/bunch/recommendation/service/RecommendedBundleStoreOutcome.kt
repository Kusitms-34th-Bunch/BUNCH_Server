package com.bunch.recommendation.service

import java.time.LocalDate

/**
 * What the saving of the calculation of a bunch did to its recommended bundles. [unchanged] are the bundles that had
 * the same photos and gave the same result again. [staleDays] are days the calculation did not touch (days that wait
 * for the analysis) whose bundles had a photo that now belongs to a bundle of another day: the photo was taken out of
 * them, and the days have to be calculated again.
 */
data class RecommendedBundleStoreOutcome(
    val created: Int,
    val updated: Int,
    val unchanged: Int,
    val removed: Int,
    val staleDays: Set<LocalDate>
)
