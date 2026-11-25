package com.bunch.recommendation.domain.combination

import java.time.Clock
import java.time.LocalDate

/**
 * The recommended bundles of the photos of a bunch (recommended bundle specification 2, 1 principle 5): the one call
 * that runs the steps of a day in their order, and gives for each day its bundles, with the card put on each and the
 * trace that tells how it was made (11).
 *
 * In this order, for each day: the day is made ready, or is held or blocked (5, 5-6, [DayPreparer]); the photos are
 * made into general bundles (7, [GeneralBundleFinder]); the photos with a date only are given to a bundle (7-9,
 * [DateOnlyAttacher]); the card is chosen for each bundle (8, [CardSelector]); and the bundles of the day are named
 * (9, [BundleNamer]). There are no event bundles. The year of a title is shown when it is not the year of today, which
 * is the day of the time of [clock] in the zone of the settings (the zone of the clock itself does not matter). That is
 * the only place the clock is read, and it is read when a day is named, never when the combiner is made.
 *
 * The bundles of a day are in the order of the time they start; the order of the list that is shown is for the step
 * that shows it (10-5). Nothing is saved or asked of the AI here, and the result is the same for the same photos and
 * the same day, whatever the order the photos come in (1.5, 1.8).
 */
class BundleCombiner(
    private val settings: CombinationSettings = CombinationSettings(),
    private val clock: Clock = Clock.system(settings.defaultZone)
) {
    private val preparer = DayPreparer(settings)
    private val general = GeneralBundleFinder(settings)
    private val attacher = DateOnlyAttacher()
    private val cards = CardSelector(settings)
    private val namer = BundleNamer(settings)

    fun combine(photos: List<RecoPhoto>): CombinationResult {
        val prepared = preparer.prepare(photos)
        return CombinationResult(
            days = prepared.days.map(::combineDay),
            excludedPhotoIds = prepared.excludedPhotoIds
        )
    }

    fun combineDay(day: DayPreparation): DayCombination = when (day) {
        is DayPreparation.Held -> DayCombination.Held(day.dayKey, day.pendingPhotoIds)
        is DayPreparation.Blocked -> DayCombination.Blocked(day.dayKey, day.findings)
        is DayPreparation.Ready -> DayCombination.Bundled(day.dayKey, bundlesOf(day))
    }

    private fun bundlesOf(day: DayPreparation.Ready): List<RecoBundle> {
        val sorted = attacher.attach(day, general.find(day).bundles)
            .map { cards.select(it, day) }
            .sortedWith(compareBy({ it.startAt }, { bundle -> bundle.photos.minOf { it.photoId } }))
        return namer.name(sorted, day, LocalDate.ofInstant(clock.instant(), settings.defaultZone))
    }
}
