package com.bunch.recommendation.domain.combination

/**
 * Which recommended bundles the list shows, and in what order (recommended bundle specification 10-5). The bundles that
 * are not shown are still kept: only what is shown is made less, the rules of the grouping stay as they are.
 *
 * - Of the general bundles of the same activity, the most recent [CombinationSettings.sameActivityGeneralMax] are
 *   shown, so a bunch that does the same thing every week, or a bunch that uploads the photos of a year at once, does
 *   not fill the list. The ones with a group photo are chosen first, and among them, and among the others, the more
 *   recent day first. An event bundle is never cut by this.
 * - The list is in the order of the kind (the events first), then the more recent day, then the later start.
 * - The list has at most [CombinationSettings.listMax] bundles.
 *
 * Bundles that cannot be told apart by these rules come in the order of their numbers, the greater first, so the same
 * bundles are always listed the same way.
 */
class RecommendedListRules(
    private val settings: CombinationSettings = CombinationSettings()
) {
    /** The numbers of the bundles that are shown, in the order they are listed. */
    fun select(candidates: List<ListCandidate>): List<Long> {
        val events = candidates.filter { it.kind == BundleKind.EVENT }
        val generals = candidates.filter { it.kind == BundleKind.GENERAL }
            .groupBy { it.activity }
            .values
            .flatMap { sameActivity -> sameActivity.sortedWith(chosenFirst).take(settings.sameActivityGeneralMax) }

        return (events + generals)
            .sortedWith(listOrder)
            .take(settings.listMax)
            .map { it.id }
    }

    // The group photo first, then the more recent day, then the later start, then the greater number.
    private val chosenFirst: Comparator<ListCandidate> = compareByDescending<ListCandidate> { it.hasGroupPhoto }
        .thenByDescending { it.dayKey }
        .thenByDescending { it.startAt }
        .thenByDescending { it.id }

    // The events first, then the more recent day, then the later start, then the greater number.
    private val listOrder: Comparator<ListCandidate> = compareBy<ListCandidate> { it.kind.ordinal }
        .thenByDescending { it.dayKey }
        .thenByDescending { it.startAt }
        .thenByDescending { it.id }
}
