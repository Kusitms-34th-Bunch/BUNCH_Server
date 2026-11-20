package com.bunch.recommendation.domain.combination

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Puts the time of a photo on the two clocks the combination needs (recommended bundle specification 5-2): the local
 * clock of the place where it was taken, for the day and the part of the day, and the absolute clock (UTC), for the
 * gaps, so that members whose devices are in other time zones do not shift each other.
 *
 * The local time is the shooting time; the absolute time is the local time minus the offset, and a photo without an
 * offset is read in the default zone. A correction of the clock ([TimedPhoto.shift]) moves both.
 */
class TimeNormalizer(
    private val settings: CombinationSettings = CombinationSettings()
) {
    /** The photo as it came in, with its own date, time and offset and nothing borrowed or corrected. */
    fun start(photo: RecoPhoto): TimedPhoto = TimedPhoto(
        photo = photo,
        date = photo.capturedDate,
        time = photo.capturedTime,
        offset = photo.utcOffset
    )

    /** The shooting time on the local clock, or null when the date or the time is not known. */
    fun localTime(photo: TimedPhoto): LocalDateTime? {
        val date = photo.date ?: return null
        val time = photo.time ?: return null
        return LocalDateTime.of(date, time).plus(photo.shift)
    }

    /** The shooting time on the absolute clock, or null when the date or the time is not known. */
    fun instant(photo: TimedPhoto): Instant? {
        val local = localTime(photo) ?: return null
        return photo.offset?.let { local.toInstant(it) } ?: local.atZone(settings.defaultZone).toInstant()
    }

    /**
     * The day of the recommendation the photo belongs to, or null when its date is not known. A day starts at
     * [CombinationSettings.dayStartHour]: a photo with a time belongs to the day of the local time minus that many
     * hours, so 02:00 on the 20th is a photo of the 19th. A photo with a date only belongs to that date.
     */
    fun dayKey(photo: TimedPhoto): LocalDate? {
        val local = localTime(photo)
        if (local != null) return local.minusHours(settings.dayStartHour.toLong()).toLocalDate()
        return photo.date
    }
}
