package com.bunch.recommendation.domain.combination

/**
 * What the event step finds in one day (recommended bundle specification 6): the events that became bundles, and the
 * events that stood but had too few photos or members to be a bundle. The general bundles take both as input: they cut
 * the day at the range of each [confirmed] event, and leave the range of a [failed] one whole (7-2).
 */
data class EventResult(
    val confirmed: List<Confirmed>,
    val failed: List<Failed>
) {
    /**
     * An event that is a bundle. [core] is from the first clue photo to the last, [range] is the core widened to where
     * the photos go on (6-3). [cluePhotoIds] are all photos that show a clue, a low quality one too. [relatedPhotoIds]
     * are the photos of the range that count for the bundle (6-4).
     */
    data class Confirmed(
        val type: EventType,
        val establishedBy: EstablishedBy,
        val core: TimeRange,
        val range: TimeRange,
        val cluePhotoIds: List<Long>,
        val relatedPhotoIds: List<Long>
    ) {
        init {
            require(!range.start.isAfter(core.start) && !core.end.isAfter(range.end)) {
                "the range of an event holds its core"
            }
        }
    }

    /**
     * An event that stood but is not a bundle (6-5). Its clue photos are marked, so a general bundle may use one (8-2).
     */
    data class Failed(
        val type: EventType,
        val cluePhotoIds: List<Long>
    )
}
