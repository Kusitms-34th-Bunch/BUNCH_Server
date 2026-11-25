package com.bunch.recommendation.domain.combination

import java.time.Instant

/**
 * What the list of the recommended bundles needs to know of one bundle to decide where it is shown (photo box 4.2.9
 * rule 6). [id] is the number the bundle is kept with, not a photo. [uploaderCount] and [photoCount] are the numbers of
 * the card (the members who uploaded the photos of the card, and the photos of the card), which are the numbers the
 * card shows.
 */
data class ListCandidate(
    val id: Long,
    val lastPhotoAddedAt: Instant,
    val uploaderCount: Int,
    val photoCount: Int
)
