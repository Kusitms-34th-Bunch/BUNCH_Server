package com.bunch.recommendation.domain.combination

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * A photo as the preparation of a day works on it (recommended bundle specification 5-2, 5-3, 5-5). It holds the
 * photo as it came in, and next to it the date and the time the calculation uses: the ones of the photo, or the ones
 * that were borrowed from another photo ([borrowedFrom]), moved by the correction of the clock ([shift]). The photo
 * itself is never changed (5-3, 5-5: what is borrowed or corrected is only for the calculation).
 *
 * [offset] belongs to the time: it is the offset of the place where the time was taken, so a borrowed time comes with
 * the offset of the photo it was borrowed from. Without an offset the time is read in the default zone (5-2).
 */
data class TimedPhoto(
    val photo: RecoPhoto,
    val date: LocalDate?,
    val time: LocalTime?,
    val offset: ZoneOffset?,
    val borrowedFrom: Long? = null,
    val shift: Duration = Duration.ZERO
) {
    init {
        require(time == null || date != null) {
            "a time requires a date"
        }
    }

    val photoId: Long
        get() = photo.photoId

    /** Whether both the date and the time are known, the photos that make the gaps of a day (5-1). */
    val hasTime: Boolean
        get() = date != null && time != null
}
