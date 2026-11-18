package com.bunch.recommendation.domain.combination

/**
 * An event that stood in a day (recommended bundle specification 6-1) and is not yet known to be a bundle: [cluePhotos]
 * are the photos of the one stretch of clues it stands on, in the order of the time, and [core] runs from the first of
 * them to the last. [strongPhotoIds], the signals, the kinds and [mergedEvents] say how it stood, for the trace of the
 * bundle (11); [mergedEvents] are the events that were in the same place and were taken into this one (6-2).
 */
data class EventCandidate(
    val type: EventType,
    val establishedBy: EstablishedBy,
    val cluePhotos: List<PreparedPhoto>,
    val strongPhotoIds: List<Long>,
    val confirmSignals: List<RuleTrace.ConfirmSignal>,
    val supportKinds: List<EventClueCode>,
    val weakKinds: List<EventClueCode>,
    val core: TimeRange,
    val mergedEvents: List<RuleTrace.MergedEvent> = emptyList()
) {
    val cluePhotoIds: List<Long>
        get() = cluePhotos.map { it.photoId }
}
