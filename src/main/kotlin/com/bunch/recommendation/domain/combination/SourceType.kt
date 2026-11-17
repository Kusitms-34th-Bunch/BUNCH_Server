package com.bunch.recommendation.domain.combination

/**
 * Where the file of a photo comes from (recommended bundle specification 3-6 and 4-4). Only a [CAMERA] photo is a
 * usable photo or an event clue (3-1, 5-1). [UNKNOWN] is a photo of which the origin is not known, for example one that
 * was uploaded before the app told it; it is not a camera photo. How a missing stored value becomes one of these is
 * decided where the input is built.
 */
enum class SourceType {
    CAMERA,
    SCREENSHOT,
    SAVED,
    UNKNOWN
}
