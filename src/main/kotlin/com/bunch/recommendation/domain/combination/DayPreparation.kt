package com.bunch.recommendation.domain.combination

import java.time.LocalDate

/**
 * What the preparation says of one day of the recommendation (recommended bundle specification 5-6). It only says;
 * keeping or deleting the bundles that the day had is done where they are saved.
 */
sealed interface DayPreparation {
    val dayKey: LocalDate

    /** The photos of the day are ready for the bundles, in the order of the time, then the photo ID. */
    data class Ready(
        override val dayKey: LocalDate,
        val photos: List<PreparedPhoto>,
        val duplicateGroups: List<DuplicateGroup>
    ) : DayPreparation

    /** The day waits: some photos are still being analyzed. The bundles it had stay as they are. */
    data class Held(
        override val dayKey: LocalDate,
        val pendingPhotoIds: List<Long>
    ) : DayPreparation

    /** The day is not recommended, the bundles of an event and the others alike. The bundles it had are deleted. */
    data class Blocked(
        override val dayKey: LocalDate,
        val findings: List<SensitiveFinding>
    ) : DayPreparation
}

/** A sensitive clue that the AI saw in a photo with enough confidence to stop the day (5-6). */
data class SensitiveFinding(
    val photoId: Long,
    val clue: SensitiveClueCode,
    val confidence: Double
)

/**
 * The preparation of the photos of a bunch. [days] are in the order of the days. [excludedPhotoIds] are the photos
 * left out of the calculation: the photos without a date (X2) and the photos of an upload batch taken with a wrong
 * clock (X5), in the order of the photo IDs (3-1).
 */
data class PreparationResult(
    val days: List<DayPreparation>,
    val excludedPhotoIds: List<Long>
)
