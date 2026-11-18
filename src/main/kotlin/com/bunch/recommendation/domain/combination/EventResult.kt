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
     * are the photos of the range that belong to the bundle (6-4), and [leftoverPhotoIds] the other photos of the
     * range, which the general bundles judge by themselves inside the range (7-2).
     *
     * The rest says how the event stood and what it took in, for the trace of the bundle (11): the strong clue photos,
     * the signs that confirmed them, the kinds of support and weak clues, the events of the same place that were taken
     * in (6-2), and how many photos each way of being related brought in (6-4). They have no value in a bundle that was
     * made by hand, as in a test.
     */
    data class Confirmed(
        val type: EventType,
        val establishedBy: EstablishedBy,
        val core: TimeRange,
        val range: TimeRange,
        val cluePhotoIds: List<Long>,
        val relatedPhotoIds: List<Long>,
        val leftoverPhotoIds: List<Long> = emptyList(),
        val strongPhotoIds: List<Long> = emptyList(),
        val confirmSignals: List<RuleTrace.ConfirmSignal> = emptyList(),
        val supportKinds: List<EventClueCode> = emptyList(),
        val weakKinds: List<EventClueCode> = emptyList(),
        val mergedEvents: List<RuleTrace.MergedEvent> = emptyList(),
        val relatedBy: RuleTrace.RelatedBy = RuleTrace.RelatedBy(clue = 0, person = 0, sameScene = 0)
    ) {
        init {
            require(!range.start.isAfter(core.start) && !core.end.isAfter(range.end)) {
                "the range of an event holds its core"
            }
        }

        /** The part of the trace of a bundle that the event step fills (11). */
        fun trace(): RuleTrace.Event = RuleTrace.Event(
            type = type,
            establishedBy = establishedBy,
            strongPhotoIds = strongPhotoIds,
            confirmSignals = confirmSignals,
            supportKinds = supportKinds,
            weakKinds = weakKinds,
            mergedEvents = mergedEvents,
            core = core,
            range = range,
            relatedBy = relatedBy
        )
    }

    /**
     * An event that stood but is not a bundle (6-5). Its clue photos are marked, so a general bundle may use one (8-2).
     */
    data class Failed(
        val type: EventType,
        val cluePhotoIds: List<Long>
    )
}
