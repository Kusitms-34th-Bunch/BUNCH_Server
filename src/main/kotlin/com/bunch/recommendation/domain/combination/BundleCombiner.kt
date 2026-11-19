package com.bunch.recommendation.domain.combination

import com.bunch.recommendation.domain.BuiltInSpecialDays
import com.bunch.recommendation.domain.SpecialDayCalendar
import java.time.Clock
import java.time.LocalDate

/**
 * The recommended bundles of the photos of a bunch (recommended bundle specification 2, 1 principle 5): the one call
 * that runs the steps of a day in their order, and gives for each day its bundles, with the card put on each and the
 * trace that tells how it was made (11).
 *
 * In this order, for each day: the day is made ready, or is held or blocked (5, 5-6, [DayPreparer]); the events that
 * stand are made into bundles (6, [EventFinder], [EventBundleBuilder]); the photos that no event took are made into
 * general bundles, with the clue photos of the events that were not bundles known to them (7, [GeneralBundleFinder]);
 * the photos with a date only are given to a bundle (7-9, [DateOnlyAttacher]); the card is chosen for each bundle
 * (8, [CardSelector]); and the bundles of the day are named (9, [BundleNamer]). The holidays of the events come from
 * [calendar]. The year of a title is shown when it is not the year of the day of [clock], which is the only place the
 * time of the clock is read.
 *
 * The bundles of a day are in the order of the time they start, the events and the general bundles together; the order
 * of the list that is shown is for the step that shows it (10-5). Nothing is saved or asked of the AI here, and the
 * result is the same for the same photos and the same day, whatever the order the photos come in (1.5, 1.8).
 */
class BundleCombiner(
    private val settings: CombinationSettings = CombinationSettings(),
    calendar: SpecialDayCalendar = BuiltInSpecialDays(),
    similarity: ImageSimilarity = FingerprintSimilarity,
    private val clock: Clock = Clock.system(settings.defaultZone)
) {
    private val preparer = DayPreparer(settings, similarity)
    private val events = EventFinder(settings, windows = EventDateWindows.of(settings, calendar))
    private val general = GeneralBundleFinder(settings)
    private val eventBundles = EventBundleBuilder()
    private val attacher = DateOnlyAttacher()
    private val cards = CardSelector(settings, similarity = similarity)
    private val namer = BundleNamer(settings)

    fun combine(photos: List<RecoPhoto>): CombinationResult {
        val prepared = preparer.prepare(photos)
        return CombinationResult(
            days = prepared.days.map(::combineDay),
            excludedPhotoIds = prepared.excludedPhotoIds,
            movedDays = prepared.movedDays
        )
    }

    fun combineDay(day: DayPreparation): DayCombination = when (day) {
        is DayPreparation.Held -> DayCombination.Held(day.dayKey, day.pendingPhotoIds)
        is DayPreparation.Blocked -> DayCombination.Blocked(day.dayKey, day.findings)
        is DayPreparation.Ready -> DayCombination.Bundled(day.dayKey, bundlesOf(day))
    }

    private fun bundlesOf(day: DayPreparation.Ready): List<RecoBundle> {
        val found = events.find(day)
        val clues = found.failed.flatMap { it.cluePhotoIds }.toSet()
        val bundles = found.confirmed.map { eventBundles.build(day, it) } + general.find(day, found).bundles
        val sorted = attacher.attach(day, bundles)
            .map { cards.select(it, day, if (it.kind == BundleKind.GENERAL) clues else emptySet()) }
            .sortedWith(compareBy({ it.startAt }, { bundle -> bundle.photos.minOf { it.photoId } }))
        return namer.name(sorted, day, LocalDate.now(clock))
    }
}
