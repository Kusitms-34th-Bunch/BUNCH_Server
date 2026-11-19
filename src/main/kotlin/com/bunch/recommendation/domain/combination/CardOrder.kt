package com.bunch.recommendation.domain.combination

import java.time.Instant
import java.time.LocalDate

/**
 * The order of the pictures on the card (photo box specification 4.2.3 rule 1): by the time they were taken. A photo
 * with a date only comes after the photos with a time of the same date, in the order it was uploaded (read as the
 * order of the photo numbers). A bundle over midnight goes day by day: the photos of the first date with a time, those
 * of the first date with a date only, then the same for the next date. The first picture is the cover of the card.
 */
class CardOrder {
    fun order(photos: List<PreparedPhoto>): List<PreparedPhoto> = photos.sortedWith(
        compareBy<PreparedPhoto> { dateOf(it) }
            .thenBy { it.instant == null }
            .thenBy { it.instant ?: Instant.MIN }
            .thenBy { it.photoId }
    )

    // The date of the clock where the photo was taken: of a photo with a time, and of one with a date only.
    private fun dateOf(photo: PreparedPhoto): LocalDate =
        photo.localTime?.toLocalDate() ?: photo.photo.capturedDate!!
}
