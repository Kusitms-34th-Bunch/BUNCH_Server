package com.bunch.recommendation.domain.combination

/**
 * A picture that may go on the card of a bundle (recommended bundle specification 8-3): one photo for each group of
 * duplicates, the one that stands for it (8-5), with what the choice reads of it.
 *
 * [shareMember] is the one member the photo counts for when the members are counted. A photo several members uploaded
 * is counted for one of them only (8-5). [clue] is whether the picture is a clue of the event of an event bundle, or
 * a clue of an event that was not a bundle in a general bundle: any photo of its group counts. [person] is a clue photo
 * whose subject is a person (6-4), [group] a photo of 3 people or more (3-6).
 */
data class CardCandidate(
    val photo: PreparedPhoto,
    val shareMember: Long,
    val clue: Boolean,
    val person: Boolean,
    val group: Boolean,
    val subject: PhotoSubject?
) {
    val photoId: Long
        get() = photo.photoId

    val good: Boolean
        get() = photo.photo.quality == PhotoQuality.GOOD

    companion object {
        /** The order of the time, then the photo number, a photo with no time last (1.8): the one every step uses. */
        val byTime: Comparator<CardCandidate> = PhotoOrder.byTimeThenId({ it.photo.instant }, { it.photoId })
    }
}
