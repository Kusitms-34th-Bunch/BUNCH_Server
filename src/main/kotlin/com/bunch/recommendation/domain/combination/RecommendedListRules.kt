package com.bunch.recommendation.domain.combination

/**
 * Which recommended bundles the list shows, and in what order (photo box 4.2.9 rule 6, recommended bundle specification
 * X8). The order is:
 *
 * 1. the bundle whose last photo was added most recently first (a photo of August that was uploaded in October puts its
 *    bundle on top);
 * 2. when that is the same, the one with the most members who uploaded the photos of its card;
 * 3. when that is the same, the one with the most photos on its card.
 *
 * The list has at most [CombinationSettings.listMax] bundles, so a bunch that uploads the photos of a year at once does
 * not fill the list (X8). A bundle is not cut for being of the same activity as another: there are no activities, and
 * the repeated gatherings are all shown (D2).
 *
 * Bundles that cannot be told apart by these rules come in the order of their numbers, the greater first, so the same
 * bundles are always listed the same way.
 */
class RecommendedListRules(
    private val settings: CombinationSettings = CombinationSettings()
) {
    /** The numbers of the bundles that are shown, in the order they are listed. */
    fun select(candidates: List<ListCandidate>): List<Long> =
        candidates.sortedWith(listOrder)
            .take(settings.listMax)
            .map { it.id }

    private val listOrder: Comparator<ListCandidate> = compareByDescending<ListCandidate> { it.lastPhotoAddedAt }
        .thenByDescending { it.uploaderCount }
        .thenByDescending { it.photoCount }
        .thenByDescending { it.id }
}
