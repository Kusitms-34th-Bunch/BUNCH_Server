package com.bunch.recommendation.domain.combination

import java.time.Instant
import java.time.LocalDate

/**
 * What the list of the recommended bundles needs to know of one bundle to decide whether and where it is shown
 * (recommended bundle specification 10-5). [id] is the number the bundle is kept with, not a photo.
 */
data class ListCandidate(
    val id: Long,
    val kind: BundleKind,
    val dayKey: LocalDate,
    val startAt: Instant,
    val activity: ActivityCode?,
    val hasGroupPhoto: Boolean
)
