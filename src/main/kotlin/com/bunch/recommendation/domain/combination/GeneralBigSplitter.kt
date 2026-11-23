package com.bunch.recommendation.domain.combination

import java.time.Duration
import kotlin.math.abs

/**
 * The fifth rule of the general bundles (recommended bundle specification 3-2): a piece over
 * [CombinationSettings.subBundleMax] photos is cut into sub bundles. It is cut at the boundary with the biggest gap
 * among those where both sides are recommended (3-4: enough photos and members, and a moment they took photos
 * together); when the gaps are as big, at the one where the two sides differ least in size, and then at the earlier
 * one (X9: the same input gives the same answer). What is cut off is looked at again. A piece with no such boundary is
 * left whole, however big it is.
 */
class GeneralBigSplitter(
    private val settings: CombinationSettings = CombinationSettings()
) {
    fun split(piece: GeneralPiece, counter: GeneralCounter): List<GeneralPiece> {
        if (counter.photos(piece.photos) <= settings.subBundleMax) return listOf(piece)
        val segments = piece.segments
        val candidates = (1 until segments.size).filter {
            counter.isRecommendable(photosBefore(segments, it)) && counter.isRecommendable(photosFrom(segments, it))
        }
        if (candidates.isEmpty()) return listOf(piece)
        val best = candidates.sortedWith(
            compareByDescending<Int> { gap(segments, it) }
                .thenBy { abs(counter.photos(photosBefore(segments, it)) - counter.photos(photosFrom(segments, it))) }
                .thenBy { it }
        ).first()
        val applied = piece.rules + GeneralRuleText.rule(
            RULE,
            segments[best].photos.first(),
            gap(segments, best)
        )
        val earlier = GeneralPiece(segments.subList(0, best), applied)
        val later = GeneralPiece(segments.subList(best, segments.size), applied)
        return split(earlier, counter) + split(later, counter)
    }

    private fun photosBefore(segments: List<GeneralSegment>, boundary: Int): List<PreparedPhoto> =
        segments.subList(0, boundary).flatMap { it.photos }

    private fun photosFrom(segments: List<GeneralSegment>, boundary: Int): List<PreparedPhoto> =
        segments.subList(boundary, segments.size).flatMap { it.photos }

    private fun gap(segments: List<GeneralSegment>, boundary: Int): Duration =
        Duration.between(segments[boundary - 1].end, segments[boundary].start)

    companion object {
        const val RULE = "rule5_split_big"
    }
}
