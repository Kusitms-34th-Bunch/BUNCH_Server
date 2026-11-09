package com.bunch.recommendation.domain

import java.time.LocalDate

/**
 * Photos taken together. [photoIds] follow the order of a recommended bundle: the first day by time, then the first
 * day's photos without a time, then the next day the same way. [endDate] is set only for a cluster that joined two
 * days across midnight. [qualifies] is the check made before burst photos are removed, so the final check is the
 * photo selection's.
 */
data class PhotoCluster(
    val photoIds: List<Long>,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val uploaderIds: Set<Long>,
    val qualifies: Boolean
) {
    val photoCount: Int get() = photoIds.size
}
