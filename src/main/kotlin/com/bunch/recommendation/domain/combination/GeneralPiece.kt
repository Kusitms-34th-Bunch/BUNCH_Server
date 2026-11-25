package com.bunch.recommendation.domain.combination

import java.time.Instant

/**
 * A stretch of a chunk that the rules of the cutting left whole, with the rules that cut it out, as text for the trace.
 * The [segments] are in the order of the time.
 */
data class GeneralPiece(
    val segments: List<GeneralSegment>,
    val rules: List<String>
) {
    init {
        require(segments.isNotEmpty()) {
            "a piece has at least one segment"
        }
    }

    val photos: List<PreparedPhoto>
        get() = segments.flatMap { it.photos }

    val start: Instant
        get() = segments.first().start

    val end: Instant
        get() = segments.last().end
}
