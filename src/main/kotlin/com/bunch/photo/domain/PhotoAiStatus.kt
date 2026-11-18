package com.bunch.photo.domain

/**
 * How far the AI analysis of a photo is (recommended bundle specification 3-6, 4-3). No value means the analysis was
 * never asked for. Nothing sets a value yet: the analysis comes with its own issue.
 */
enum class PhotoAiStatus {
    PENDING,
    DONE,
    FAILED
}
