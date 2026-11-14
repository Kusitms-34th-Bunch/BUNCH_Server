package com.bunch.recommendation.domain.combination

/**
 * What the AI tells about one photo, whichever model it is (recommended bundle specification 4-1 F4 to F7 and 4-3).
 * It only reads the photo; deciding what the photo means for a bundle is done by the rules.
 *
 * [clues] and [sensitiveClues] are kept apart, because a sensitive clue stops the day and never makes an event.
 * [activity], [subject] and [people] are null when they are not known: the analysis failed or has not run.
 */
data class AiAnalysis(
    val clues: List<Reading<EventClueCode>> = emptyList(),
    val sensitiveClues: List<Reading<SensitiveClueCode>> = emptyList(),
    val activity: Reading<ActivityCode>? = null,
    val subject: PhotoSubject? = null,
    val people: PeopleCount? = null
) {
    companion object {
        /** A photo the AI told nothing about: no clue, no activity, no subject, no people (4-3, failed analysis). */
        val NONE = AiAnalysis()
    }
}
