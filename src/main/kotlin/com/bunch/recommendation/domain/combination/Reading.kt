package com.bunch.recommendation.domain.combination

/**
 * One thing the AI saw in a photo and how sure it is, from 0 to 1 (recommended bundle specification 4-3). The reading
 * is kept as the AI gave it; the limits below which it counts as not seen are settings that the steps of the
 * calculation apply (4-3: "the limits are applied when it is calculated").
 */
data class Reading<T>(
    val code: T,
    val confidence: Double
) {
    init {
        require(confidence in 0.0..1.0) {
            "confidence must be from 0 to 1"
        }
    }
}
