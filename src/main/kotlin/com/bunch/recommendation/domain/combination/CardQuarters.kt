package com.bunch.recommendation.domain.combination

/**
 * Cuts the pictures of a bundle into [CombinationSettings.timeBuckets] parts of the same number of pictures, in the
 * order of the time (recommended bundle specification 8-3). A photo with no time comes last. When the pictures do not
 * divide, the parts at the front get one more each.
 */
class CardQuarters(
    private val settings: CombinationSettings = CombinationSettings()
) {
    fun of(pool: List<CardCandidate>): List<List<CardCandidate>> {
        val ordered = pool.sortedWith(CardCandidate.byTime)
        val parts = settings.timeBuckets
        val size = ordered.size / parts
        val extra = ordered.size % parts
        var start = 0
        return (0 until parts).map { index ->
            val length = size + if (index < extra) 1 else 0
            ordered.subList(start, start + length).also { start += length }
        }
    }
}
