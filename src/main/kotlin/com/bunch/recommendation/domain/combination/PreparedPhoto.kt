package com.bunch.recommendation.domain.combination

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * A photo of a day after the preparation (recommended bundle specification 3-1): the photo as it came in, and the day
 * and the times the calculation uses. [localTime] and [instant] are null for a photo with a date only, which has no
 * time to put in the order or to measure a gap with.
 */
data class PreparedPhoto(
    val photo: RecoPhoto,
    val dayKey: LocalDate,
    val localTime: LocalDateTime?,
    val instant: Instant?
) {
    val photoId: Long
        get() = photo.photoId

    /** How much of the time is known: a photo with a date only has no time. */
    val timeState: TimeState
        get() = if (localTime != null) TimeState.FULL else TimeState.DATE_ONLY
}
