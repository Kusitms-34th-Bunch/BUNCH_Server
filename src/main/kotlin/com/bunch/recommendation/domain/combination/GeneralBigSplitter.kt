package com.bunch.recommendation.domain.combination

import java.time.Duration
import kotlin.math.abs

/**
 * The seventh rule of the general bundles (recommended bundle specification 7-7): a section over
 * [CombinationSettings.subBundleMax] photos is cut into sub bundles. It is cut at the boundary with the biggest gap
 * among those where both sides are enough for a bundle; when the gaps are as big, at the one where the two sides differ
 * least in size, and then at the earlier one. What is cut off is looked at again. A section with no such boundary is
 * left whole, however big it is.
 */
class GeneralBigSplitter(
    private val settings: CombinationSettings = CombinationSettings()
) {
    /** A part of a section and the rules that cut it out, as text for the trace. */
    data class Part(
        val section: GeneralSection,
        val rules: List<String>
    )

    fun split(section: GeneralSection, counter: GeneralCounter): List<Part> =
        splitBig(section, emptyList(), counter)

    private fun splitBig(
        section: GeneralSection,
        rules: List<String>,
        counter: GeneralCounter
    ): List<Part> {
        if (counter.photos(section.photos) <= settings.subBundleMax) return listOf(Part(section, rules))
        val segments = section.segments
        val candidates = (1 until segments.size).filter {
            counter.isEnough(photosBefore(segments, it)) && counter.isEnough(photosFrom(segments, it))
        }
        if (candidates.isEmpty()) return listOf(Part(section, rules))
        val best = candidates.sortedWith(
            compareByDescending<Int> { gap(segments, it) }
                .thenBy { abs(counter.photos(photosBefore(segments, it)) - counter.photos(photosFrom(segments, it))) }
                .thenBy { it }
        ).first()
        val applied = rules + GeneralRuleText.rule(
            RULE,
            segments[best].photos.first(),
            gap(segments, best)
        )
        val earlier = GeneralSection(segments.subList(0, best), section.activity)
        val later = GeneralSection(segments.subList(best, segments.size), section.activity)
        return splitBig(earlier, applied, counter) + splitBig(later, applied, counter)
    }

    private fun photosBefore(segments: List<GeneralSegment>, boundary: Int): List<PreparedPhoto> =
        segments.subList(0, boundary).flatMap { it.photos }

    private fun photosFrom(segments: List<GeneralSegment>, boundary: Int): List<PreparedPhoto> =
        segments.subList(boundary, segments.size).flatMap { it.photos }

    private fun gap(segments: List<GeneralSegment>, boundary: Int): Duration =
        Duration.between(segments[boundary - 1].end, segments[boundary].start)

    companion object {
        const val RULE = "rule7_split_big"
    }
}
