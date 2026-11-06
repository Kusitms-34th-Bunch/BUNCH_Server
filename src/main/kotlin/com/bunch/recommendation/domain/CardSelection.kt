package com.bunch.recommendation.domain

/**
 * The photos of a recommended bundle card (4.2.8). [photoIds] are in the display order, the first one is the cover,
 * and are empty when the cluster is not [recommended]. [photoCount] is the number of photos left after the burst
 * photos were reduced, which is what the recommendation condition counts. [uploaderIds] are the members of the
 * photos that were left, or of the card photos when the cluster is [recommended].
 */
data class CardSelection(
    val photoIds: List<Long>,
    val photoCount: Int,
    val uploaderIds: Set<Long>,
    val recommended: Boolean
)
