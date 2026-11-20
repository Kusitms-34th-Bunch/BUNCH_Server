package com.bunch.recommendation.domain.combination

/**
 * Puts the card on a bundle: which pictures it holds, in which order, and which one is the cover (recommended bundle
 * specification 8, photo box specification 4.2.3 and 4.2.8).
 *
 * The pictures are the usable photos of the bundle, one for each group of duplicates ([CardPool]). A bundle with
 * [CombinationSettings.cardMaxSize] pictures or fewer puts all of them on the card, with no choice. A bigger one has
 * that many chosen by [CardPicker]. The order is the one of the time ([CardOrder]) and the first picture is the cover.
 * The bundle comes back with the places on the card, the cover and the part of the trace that tells how they were
 * chosen (11); the photos that are not on the card stay in the bundle without a place.
 *
 * [failedClueIds] are the clue photos of the events of the day that were not bundles, which a general bundle may take
 * a picture of (8-2).
 */
class CardSelector(
    private val settings: CombinationSettings = CombinationSettings(),
    eligibility: BundleEligibility = BundleEligibility(settings),
    similarity: ImageSimilarity = FingerprintSimilarity
) {
    private val pools = CardPool(eligibility, EventClues(settings))
    private val picker = CardPicker(settings, CardScorer(settings, similarity))
    private val order = CardOrder()

    fun select(
        bundle: RecoBundle,
        day: DayPreparation.Ready,
        failedClueIds: Set<Long> = emptySet()
    ): RecoBundle {
        val pool = pools.build(bundle, day, failedClueIds)
        if (pool.isEmpty()) return bundle.copy(trace = bundle.trace.copy(selection = NO_CARD))

        val size = minOf(pool.size, settings.cardMaxSize)
        val choice = if (pool.size <= settings.cardMaxSize) {
            CardPicker.Choice(pool, pool.map { RuleTrace.Pick(it.photoId, ALL) }, emptyList())
        } else {
            picker.pick(pool, size, bundle.kind == BundleKind.EVENT)
        }

        val ordered = order.order(choice.chosen.map { it.photo })
        val places = ordered.mapIndexed { index, photo -> photo.photoId to index + 1 }.toMap()
        return bundle.copy(
            photos = bundle.photos.map { it.copy(cardPosition = places[it.photoId]) },
            coverPhotoId = ordered.first().photoId,
            trace = bundle.trace.copy(selection = RuleTrace.Selection(size, choice.picks, choice.relaxed))
        )
    }

    companion object {
        /** The reason of a picture on a card that holds all the pictures of its bundle. */
        const val ALL = "P0_all"

        private val NO_CARD = RuleTrace.Selection(0, emptyList(), emptyList())
    }
}
