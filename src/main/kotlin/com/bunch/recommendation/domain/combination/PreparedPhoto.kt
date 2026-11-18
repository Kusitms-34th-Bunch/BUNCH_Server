package com.bunch.recommendation.domain.combination

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * A photo of a day after the preparation (recommended bundle specification 5): the photo as it came in, and the day
 * and the times the calculation uses. [localTime] and [instant] are null for a photo with a date only, which has no
 * time to put in the order or to measure a gap with (5-1).
 *
 * [borrowedFrom] is the photo whose date and time were borrowed (5-3), and [clockShift] the move of the clock that was
 * corrected (5-5); both are only for the calculation, and [photo] is not changed.
 */
data class PreparedPhoto(
    val photo: RecoPhoto,
    val dayKey: LocalDate,
    val localTime: LocalDateTime?,
    val instant: Instant?,
    val borrowedFrom: Long?,
    val clockShift: Duration
) {
    val photoId: Long
        get() = photo.photoId

    /** How much of the time is known now: the time of a photo that borrowed one is known. */
    val timeState: TimeState
        get() = if (localTime != null) TimeState.FULL else TimeState.DATE_ONLY
}
