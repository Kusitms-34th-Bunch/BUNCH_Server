package com.bunch.recommendation.domain.combination

/**
 * A picture that may go on the card of a bundle (recommended bundle specification 4): one photo for each group of
 * duplicates, the one that stands for it, with what the choice reads of it.
 *
 * [shareMember] is the one member the photo counts for when the members are counted. A photo several members uploaded
 * is counted for one of them only. [taker] is the member who is taken to have taken the photo, the one who uploaded the
 * group first (the same member the together moment counts, R2): a copy another member saved and uploaded again is not
 * a photo that member took.
 */
data class CardCandidate(
    val photo: PreparedPhoto,
    val shareMember: Long,
    val taker: Long
) {
    val photoId: Long
        get() = photo.photoId

    val good: Boolean
        get() = photo.photo.quality == PhotoQuality.GOOD

    companion object {
        /** The order of the time, then the photo number, a photo with no time last (1.8): the one every step uses. */
        val byTime: Comparator<CardCandidate> = PhotoOrder.byTimeThenId({ it.photo.instant }, { it.photoId })

        /**
         * The order of the time, then the photo uploaded earlier, then the lower photo number, a photo with no time
         * last (X9: the same pictures are always put in the same order). The choice and the cover use it for a tie.
         */
        val byTimeThenUpload: Comparator<CardCandidate> = compareBy(
            { it.photo.instant == null },
            { it.photo.instant },
            { it.photo.photo.uploadedAt },
            { it.photoId }
        )
    }
}
