package com.bunch.recommendation.domain.combination

/**
 * A stretch of a chunk that the people and time rules (7-4) left whole, with the rules that cut it out, as text for the
 * trace. The [segments] are in the order of the time.
 */
data class GeneralPiece(
    val segments: List<GeneralSegment>,
    val rules: List<String>
) {
    val photos: List<PreparedPhoto>
        get() = segments.flatMap { it.photos }
}
