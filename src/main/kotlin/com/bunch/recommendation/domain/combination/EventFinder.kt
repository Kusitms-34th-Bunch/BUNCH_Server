package com.bunch.recommendation.domain.combination

/**
 * The events of a day and the bundles they make (recommended bundle specification 6). It takes a day that is ready and
 * gives the events that are bundles and the events that stood but are not: the general bundles cut the day at the
 * range of the first, and leave the range of the second whole (7-2).
 *
 * In this order: the events that stand (6-1), one of those in the same place (6-2), the range of each (6-3), and the
 * photos of the range that belong to it (6-4). An event that stood but has too few photos or members is not a bundle,
 * and it is treated as if it had never stood: it is taken out, its clue photos are marked for the general bundle that
 * takes them (6-5), and the ranges of the others are made again, because the core of that event stopped them. This goes
 * on until no event is taken out any more.
 */
class EventFinder(
    settings: CombinationSettings = CombinationSettings(),
    mapping: EventClueMapping = EventClueMapping.DEFAULT,
    windows: EventDateWindows = EventDateWindows(settings),
    eligibility: BundleEligibility = BundleEligibility(settings)
) {
    private val clues = EventClues(settings, mapping)
    private val establisher = EventEstablisher(settings, clues, mapping, windows)
    private val merger = EventPlaceMerger(settings)
    private val ranges = EventRangeBuilder(settings)
    private val collector = EventPhotoCollector(clues, eligibility)

    fun find(day: DayPreparation.Ready): EventResult {
        var active = merger.merge(establisher.establish(day), day)
        val failed = mutableListOf<EventCandidate>()
        while (true) {
            val collected = ranges.build(day, active).map { it to collector.collect(it, day) }
            val notBundles = collected.filter { !it.second.enough }.map { it.first.candidate }
            if (notBundles.isEmpty()) return result(collected, failed)
            failed += notBundles
            active = active.filter { it !in notBundles }
        }
    }

    private fun result(
        collected: List<Pair<EventRangeBuilder.Ranged, EventPhotoCollector.Collected>>,
        failed: List<EventCandidate>
    ): EventResult = EventResult(
        confirmed = collected.map { (ranged, photos) ->
            val event = ranged.candidate
            EventResult.Confirmed(
                type = event.type,
                establishedBy = event.establishedBy,
                core = event.core,
                range = ranged.range,
                cluePhotoIds = event.cluePhotoIds,
                relatedPhotoIds = photos.related.map { it.photoId },
                leftoverPhotoIds = photos.leftover.map { it.photoId },
                strongPhotoIds = event.strongPhotoIds,
                confirmSignals = event.confirmSignals,
                supportKinds = event.supportKinds,
                weakKinds = event.weakKinds,
                mergedEvents = event.mergedEvents,
                relatedBy = photos.relatedBy
            )
        }.sortedWith(compareBy({ it.core.start }, { it.type })),
        failed = failed.sortedWith(compareBy({ it.core.start }, { it.type }))
            .map { EventResult.Failed(it.type, it.cluePhotoIds) }
    )
}
