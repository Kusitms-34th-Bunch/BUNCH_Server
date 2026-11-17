package com.bunch.recommendation.domain.combination

/** Why a photo belongs to a recommended bundle (recommended bundle specification 4-5, 6-4 and 7-9). */
enum class PhotoRole {
    /** A photo that shows a clue of the event. A low quality one is kept here but is not counted (6-4). */
    CLUE,

    /** A photo of an event that is not a clue: with people, or a scene like a clue (6-4). */
    RELATED,

    /** A photo of a general bundle. */
    GENERAL,

    /** A photo with a date but no time, put to the bundle at the end (7-9). */
    ATTACHED_DATE_ONLY
}
