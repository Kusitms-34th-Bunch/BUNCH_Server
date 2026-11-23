package com.bunch.recommendation.domain.combination

/**
 * Puts the card on a bundle: which pictures it holds, in which order, and which one is the cover (recommended bundle
 * specification 4, photo box specification 4.2.3).
 *
 * The pictures are the usable photos of the bundle, one for each group of duplicates ([CardPool]). The card holds the
 * number the table of the specification gives for them ([CombinationSettings.cardSizeFor]: 6 pictures for 6 to 15, 8
 * for 16 to 25, 10 from 26), and a bundle with no more pictures than that puts all of them on the card, with no
 * choice. A bigger one has that many chosen by [CardPicker]. The order on the card is the one of the time
 * ([CardOrder]). The cover is chosen by [CardCover] and is not the first picture. The bundle comes back with the places
 * on the card, the cover and the part of the trace that tells how they were chosen (11); the photos that are not on the
 * card stay in the bundle without a place.
 */
class CardSelector(
    private val settings: CombinationSettings = CombinationSettings(),
    private val eligibility: BundleEligibility = BundleEligibility(settings)
) {
    private val pools = CardPool(eligibility)
    private val picker = CardPicker(settings)
    private val moments = MomentMembers(settings.cardWindow)
    private val cover = CardCover()
    private val order = CardOrder()

    fun select(bundle: RecoBundle, day: DayPreparation.Ready): RecoBundle {
        val pool = pools.build(bundle, day)
        if (pool.isEmpty()) return bundle.copy(trace = bundle.trace.copy(selection = NO_CARD))

        val byId = day.photos.associateBy { it.photoId }
        val inBundle = bundle.photos.mapNotNull { byId[it.photoId] }
        val together = eligibility.togetherMoments(inBundle, day.duplicateGroups).photoIds

        val around = moments.of(pool)
        val size = minOf(pool.size, settings.cardSizeFor(pool.size) ?: pool.size)
        val choice = if (pool.size <= size) {
            CardPicker.Choice(pool, pool.map { RuleTrace.Pick(it.photoId, ALL) }, emptyList())
        } else {
            picker.pick(pool, size, together, around)
        }

        val ordered = order.order(choice.chosen.map { it.photo })
        val places = ordered.mapIndexed { index, photo -> photo.photoId to index + 1 }.toMap()
        val covered = cover.choose(choice.chosen, together, around)
        return bundle.copy(
            photos = bundle.photos.map { it.copy(cardPosition = places[it.photoId]) },
            coverPhotoId = covered.cover.photoId,
            trace = bundle.trace.copy(
                selection = RuleTrace.Selection(size, choice.picks, choice.relaxed, covered.by)
            )
        )
    }

    companion object {
        /** The reason of a picture on a card that holds all the pictures of its bundle. */
        const val ALL = "P0_all"

        private val NO_CARD = RuleTrace.Selection(0, emptyList(), emptyList())
    }
}
