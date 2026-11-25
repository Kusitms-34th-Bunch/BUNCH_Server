package com.bunch.recommendation.domain.combination

import java.time.LocalDate

/**
 * The preparation of the days of a bunch before any bundle is made (recommended bundle specification 1, 3-1, 6). It
 * takes the photos of the bunch as the input builder made them and gives, for each day of the recommendation, the
 * photos ready for the bundles, or the word that the day waits or is not recommended.
 *
 * In this order: the photos taken with a wrong clock are left out (X5, [ClockErrorFilter]); the photos without a date
 * are left out (X2); the times of the rest are put on the clocks (X4); the photos are given to their days; the photos
 * of a day that are the same shot are grouped (R1); and the day is stopped when a photo is still being analyzed, or
 * when a photo shows a funeral or a hospital. Nothing is borrowed from another photo and no clock is corrected: the
 * picture is never looked at, and what cannot be told from the metadata is left out.
 *
 * A photo with a date and no time stays. It has no place on the time axis, and the step that gives such photos to a
 * bundle takes it later (X3, [DateOnlyAttacher]). A day with a photo that is still being analyzed waits, and that comes
 * before the sensitive clues: the clues are not all known until the analysis is done. A sensitive clue counts when the
 * AI is sure enough (the setting sensitiveMinConfidence), and one photo is enough, with no other sign.
 *
 * The result is the same for the same photos, whatever the order they come in (1.5, 1.8). Nothing is saved, and the
 * photos themselves are not changed.
 */
class DayPreparer(
    private val settings: CombinationSettings = CombinationSettings()
) {
    private val times = TimeNormalizer(settings)
    private val clockErrors = ClockErrorFilter(times, settings.clockErrorMargin)
    private val grouper = DuplicateGrouper(times, settings.burstMaxGap)

    fun prepare(photos: List<RecoPhoto>): PreparationResult {
        val started = photos.sortedBy { it.photoId }.map(times::start)
        val wrongClock = clockErrors.find(started)
        val usable = started.filter { it.photoId !in wrongClock }
        val (dated, undated) = usable.partition { it.date != null }
        val days = dated.groupBy { times.dayKey(it)!! }.toSortedMap()
        return PreparationResult(
            days = days.map { (day, dayPhotos) -> prepareDay(day, dayPhotos) },
            excludedPhotoIds = (wrongClock + undated.map { it.photoId }).sorted()
        )
    }

    private fun prepareDay(day: LocalDate, photos: List<TimedPhoto>): DayPreparation {
        val ordered = photos.sortedWith(PhotoOrder.byTimeThenId<TimedPhoto>({ times.instant(it) }, { it.photoId }))
        val pending = ordered.filter { it.photo.aiStatus == AiStatus.PENDING }
        if (pending.isNotEmpty()) return DayPreparation.Held(day, pending.map { it.photoId })

        val findings = ordered.flatMap { photo ->
            photo.photo.analysis.sensitiveClues
                .filter { it.confidence >= settings.sensitiveMinConfidence }
                .map { SensitiveFinding(photo.photoId, it.code, it.confidence) }
        }
        if (findings.isNotEmpty()) return DayPreparation.Blocked(day, findings)

        return DayPreparation.Ready(day, ordered.map(::prepared), grouper.group(ordered))
    }

    private fun prepared(photo: TimedPhoto): PreparedPhoto = PreparedPhoto(
        photo = photo.photo,
        dayKey = times.dayKey(photo)!!,
        localTime = times.localTime(photo),
        instant = times.instant(photo)
    )
}
