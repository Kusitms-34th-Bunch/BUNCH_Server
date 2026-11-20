package com.bunch.recommendation.domain.combination

/**
 * The score that decides which picture takes a free place on the card (recommended bundle specification 8-4).
 *
 * The score is the quality (good 1.0, normal 0.5) and the diversity: how little the picture looks like the ones that
 * are chosen already, one minus the biggest similarity, and 1 while nothing is chosen. The first version has no
 * embedding, so the similarity is the one of the fingerprints, and the representativeness of the picture (how much it
 * looks like the other pictures of the bundle) is not used: it counts as 0 for all, and its weight is left out. A
 * similarity that cannot be told counts as not alike.
 */
class CardScorer(
    private val settings: CombinationSettings = CombinationSettings(),
    private val similarity: ImageSimilarity = FingerprintSimilarity
) {
    fun score(candidate: CardCandidate, chosen: List<CardCandidate>): Double {
        val quality = if (candidate.good) GOOD else NORMAL
        val likeness = chosen.maxOfOrNull { similarity.similarity(candidate.photo.photo, it.photo.photo) ?: 0.0 } ?: 0.0
        return settings.qualityWeight * quality + settings.diversityWeight * (1.0 - likeness)
    }

    private companion object {
        const val GOOD = 1.0
        const val NORMAL = 0.5
    }
}
