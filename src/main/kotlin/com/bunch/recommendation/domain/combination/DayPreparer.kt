package com.bunch.recommendation.domain.combination

import java.time.Duration
import java.time.LocalDate

/**
 * The preparation of the days of a bunch before any bundle is made (recommended bundle specification 5). It takes the
 * photos of the bunch as the input builder made them and gives, for each day of the recommendation, the photos ready
 * for the bundles, or the word that the day waits or is not recommended.
 *
 * In this order: the times of the photos are put on the clocks (5-2); the photos that miss a date or a time borrow it
 * (5-3); the clocks that are wrong are corrected (5-5); the photos are given to their days; the photos of a day that
 * show the same picture are grouped (5-4); and the day is stopped when a photo is still being analyzed, or when a
 * photo shows a funeral or a hospital (5-6). The clocks are corrected before the groups are made, because photos of one
 * scene taken by devices that are ten minutes or more apart are not close in time until the clock is corrected.
 *
 * A day with a photo that is still being analyzed waits, and that comes before the sensitive clues: the clues are not
 * all known until the analysis is done. A sensitive clue counts when the AI is sure enough (the setting
 * sensitiveMinConfidence), and one photo is enough, with no other sign (5-6).
 *
 * The result is the same for the same photos, whatever the order they come in (1.5, 1.8). Nothing is saved, and the
 * photos themselves are not changed: what is borrowed or corrected stays in the result.
 */
class DayPreparer(
    private val settings: CombinationSettings = CombinationSettings(),
    similarity: ImageSimilarity = FingerprintSimilarity,
    burstMinSimilarity: Double = settings.fingerprintBurstMinSimilarity,
    sameCompositionMinSimilarity: Double = settings.fingerprintSameCompositionMinSimilarity
) {
    private val times = TimeNormalizer(settings)
    private val borrower = DateBorrower(times, similarity, sameCompositionMinSimilarity)
    private val corrector = ClockCorrector(
        times,
        similarity,
        sameCompositionMinSimilarity,
        settings.clockCorrectionMinPairs,
        settings.clockCorrectionMinMedian
    )
    private val grouper = DuplicateGrouper(
        times,
        similarity,
        settings.burstMaxGap,
        burstMinSimilarity,
        settings.sameCompositionMaxGap,
        sameCompositionMinSimilarity
    )

    fun prepare(photos: List<RecoPhoto>): PreparationResult {
        val borrowed = borrower.borrow(photos.sortedBy { it.photoId }.map(times::start))
        val dated = borrowed.filter { it.date != null }
        val corrected = corrector.correct(dated)
        val days = corrected.groupBy { times.dayKey(it)!! }.toSortedMap()
        return PreparationResult(
            days = days.map { (day, dayPhotos) -> prepareDay(day, dayPhotos) },
            excludedPhotoIds = borrowed.filter { it.date == null }.map { it.photoId }.sorted(),
            movedDays = movedDays(corrected)
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
        instant = times.instant(photo),
        borrowedFrom = photo.borrowedFrom,
        clockShift = photo.shift
    )

    // The days a corrected photo left and the days it went to, for a photo that the correction moved to another day.
    private fun movedDays(corrected: List<TimedPhoto>): Set<LocalDate> = corrected
        .filter { !it.shift.isZero }
        .flatMap { photo ->
            val before = times.dayKey(photo.copy(shift = Duration.ZERO))!!
            val after = times.dayKey(photo)!!
            if (before == after) emptyList() else listOf(before, after)
        }
        .toSet()
}
