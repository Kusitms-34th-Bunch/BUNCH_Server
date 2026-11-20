package com.bunch.photo.domain

/**
 * How far the AI analysis of a photo is (recommended bundle specification 3-6, 4-3). No value means the analysis was
 * never asked for. The analysis service sets the value; nothing asks for an analysis yet, because that waits for the
 * member's agreement to send the photo to an AI service.
 */
enum class PhotoAiStatus {
    PENDING,
    DONE,
    FAILED
}
