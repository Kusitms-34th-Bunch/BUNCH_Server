package com.bunch.recommendation.domain.combination

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * A photo as the preparation of a day works on it (recommended bundle specification 3-1, X4). It holds the photo as it
 * came in, and next to it the date, the time and the offset the calculation uses: the ones of the photo. Nothing is
 * borrowed from another photo and no clock is moved, so these are the values the photo was uploaded with; the photo
 * itself is never changed.
 *
 * [offset] is the offset of the place where the time was taken. Without an offset the time is read in the default zone.
 */
data class TimedPhoto(
    val photo: RecoPhoto,
    val date: LocalDate?,
    val time: LocalTime?,
    val offset: ZoneOffset?
) {
    init {
        require(time == null || date != null) {
            "a time requires a date"
        }
    }

    val photoId: Long
        get() = photo.photoId
}
