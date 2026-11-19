package com.bunch.recommendation.domain.combination

import java.time.Instant
import java.time.LocalDate

/**
 * What the list of the recommended bundles needs to know of one bundle to decide whether and where it is shown
 * (recommended bundle specification 10-5, photo box specification 4.2.9 rule 6). [id] is the number the bundle is kept
 * with, not a photo. [lastPhotoAddedAt] is when the last photo of the bundle was added to the photo box.
 * [uploaderCount] and [photoCount] are the numbers of the card the bundle is shown as: the members who uploaded the
 * photos of the card and the photos of the card.
 */
data class ListCandidate(
    val id: Long,
    val kind: BundleKind,
    val dayKey: LocalDate,
    val startAt: Instant,
    val activity: ActivityCode?,
    val hasGroupPhoto: Boolean,
    val lastPhotoAddedAt: Instant,
    val uploaderCount: Int,
    val photoCount: Int
)
