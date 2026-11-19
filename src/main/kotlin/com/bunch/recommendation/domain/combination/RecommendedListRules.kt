package com.bunch.recommendation.domain.combination

/**
 * Which recommended bundles the list shows, and in what order (recommended bundle specification 10-5, photo box
 * specification 4.2.9 rule 6). The bundles that are not shown are still kept: only what is shown is made less, the
 * rules of the grouping stay as they are.
 *
 * - Of the general bundles of the same activity, the most recent [CombinationSettings.sameActivityGeneralMax] are
 *   shown, so a bunch that does the same thing every week, or a bunch that uploads the photos of a year at once, does
 *   not fill the list. The ones with a group photo are chosen first, and among them, and among the others, the more
 *   recent day first. An event bundle is never cut by this.
 * - The list is in the order of the photo box specification: the bundle whose last photo was added most recently
 *   first (a photo of August uploaded in October puts its bundle on top), then the one with more members who uploaded
 *   the photos of its card, then the one with more photos on its card. The specification of the recommended bundles
 *   puts the events first; the photo box specification is followed, as it is the one of the screen.
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

    // The last photo added, then the members, then the photos, then the greater number (4.2.9 rule 6).
    private val listOrder: Comparator<ListCandidate> = compareByDescending<ListCandidate> { it.lastPhotoAddedAt }
        .thenByDescending { it.uploaderCount }
        .thenByDescending { it.photoCount }
        .thenByDescending { it.id }
}
