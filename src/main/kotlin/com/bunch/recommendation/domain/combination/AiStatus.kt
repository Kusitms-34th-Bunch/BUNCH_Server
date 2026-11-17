package com.bunch.recommendation.domain.combination

/**
 * How far the AI analysis of a photo is (recommended bundle specification 3-6). A day with a pending photo waits (5-6).
 */
enum class AiStatus {
    PENDING,
    DONE,
    FAILED
}
