package com.bunch.recommendation.domain.combination

/**
 * Photos of one day that show the same picture: shots of a burst, or the same composition taken again, by one member or
 * by several (recommended bundle specification 5-4). A bundle counts the photos by their groups, one for each, and
 * the members by the ones who uploaded any photo of the group (3-1).
 *
 * [id] is the lowest photo ID of the group. [photoIds] come in the order of the time, then the photo ID (1.8).
 * A photo alone is a group of its own.
 */
data class DuplicateGroup(
    val id: Long,
    val photoIds: List<Long>,
    val uploaderIds: Set<Long>
) {
    init {
        require(photoIds.isNotEmpty()) {
            "a group has at least one photo"
        }
        require(id == photoIds.min()) {
            "the ID of a group is the lowest photo ID of it"
        }
    }
}
