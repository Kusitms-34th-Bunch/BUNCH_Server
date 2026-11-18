package com.bunch.recommendation.domain.combination

import java.time.LocalDate

/**
 * What the combination says of one day of the recommendation (recommended bundle specification 2, 5-6). It only says;
 * keeping or deleting the bundles the day had, and saving the new ones, is done where they are saved (10).
 */
sealed interface DayCombination {
    val dayKey: LocalDate

    /**
     * The day was calculated: these are its bundles, the events first, in the order of the time (1.8). A day with no
     * bundle is a day with nothing to recommend (13-5 X1), and the bundles it had are replaced by none.
     */
    data class Bundled(
        override val dayKey: LocalDate,
        val bundles: List<RecoBundle>
    ) : DayCombination

    /** The day waits for photos that are still being analyzed: the bundles it had stay as they are (5-6, X6). */
    data class Held(
        override val dayKey: LocalDate,
        val pendingPhotoIds: List<Long>
    ) : DayCombination

    /** The day is not recommended, for a sensitive clue; the bundles it had are deleted (5-6, X12). */
    data class Blocked(
        override val dayKey: LocalDate,
        val findings: List<SensitiveFinding>
    ) : DayCombination
}
