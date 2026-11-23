package com.bunch.recommendation.domain.combination

/**
 * Chooses the cover of a card (recommended bundle specification 4, the cover). The cover is one of the pictures on the
 * card, and it is not the first picture in the order of the time:
 *
 * 1. A front camera photo (a selfie) of a together moment. The photos carry no information of the lens yet and the
 *    specification leaves it to the legal check (D4), so this step is left out.
 * 2. A photo of good quality of a together moment (R2).
 * 3. A photo of good quality with the most members who took photos at most [CombinationSettings.cardWindow] around it.
 * 4. When no photo on the card is good, the photo with the most members around it (the specification names no more
 *    steps, and a card must have a cover).
 *
 * Of the photos that fit a step, the one with more members around it goes first, then the earlier one, then the one
 * uploaded earlier, then the lower photo number (X9).
 */
class CardCover {
    /** The cover and the step that chose it, as text for the trace. */
    data class Choice(
        val cover: CardCandidate,
        val by: String
    )

    fun choose(card: List<CardCandidate>, together: Set<Long>, around: Map<Long, Int>): Choice {
        val order = compareByDescending<CardCandidate> { around[it.photoId] ?: 0 }
            .then(CardCandidate.byTimeThenUpload)
        val good = card.filter { it.good }
        val inMoment = good.filter { it.photoId in together }
        return when {
            inMoment.isNotEmpty() -> Choice(inMoment.minWith(order), TOGETHER_GOOD)
            good.isNotEmpty() -> Choice(good.minWith(order), GOOD_MOST_MEMBERS)
            else -> Choice(card.minWith(order), MOST_MEMBERS)
        }
    }

    companion object {
        const val TOGETHER_GOOD = "cover_together_good"
        const val GOOD_MOST_MEMBERS = "cover_good_most_members"
        const val MOST_MEMBERS = "cover_most_members"
    }
}
