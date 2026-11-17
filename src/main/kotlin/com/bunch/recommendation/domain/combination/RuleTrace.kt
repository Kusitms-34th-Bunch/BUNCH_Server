package com.bunch.recommendation.domain.combination

import java.time.LocalDate

/**
 * Why a bundle is what it is: the clues and the rules that made it, so that a wrong bundle can be traced and a bundle
 * is the same every time (recommended bundle specification 1.5 and 11). Each step fills its own part: the event step
 * [event], the general step [general], the card selection [selection].
 */
data class RuleTrace(
    val dayKey: LocalDate,
    val kind: BundleKind,
    val counts: Counts,
    val configVersion: String,
    val event: Event? = null,
    val general: General? = null,
    val selection: Selection? = null
) {
    /** The number of photos and of uploaders of the bundle, counted as the specification counts them (3-1). */
    data class Counts(
        val photos: Int,
        val uploaders: Int
    )

    /** How an event stood and what it took in (6-1 to 6-4). */
    data class Event(
        val type: EventType,
        val establishedBy: EstablishedBy,
        val strongPhotoIds: List<Long>,
        val confirmSignals: List<ConfirmSignal>,
        val supportKinds: List<EventClueCode>,
        val weakKinds: List<EventClueCode>,
        val mergedEvents: List<MergedEvent>,
        val core: TimeRange,
        val range: TimeRange,
        val relatedBy: RelatedBy
    )

    /** The three signals that confirm a strong clue (6-1): the same clue in another photo, other clues, a person. */
    enum class ConfirmSignal {
        SAME_CLUE_IN_OTHER_PHOTO,
        OTHER_CLUE_KINDS,
        PERSON
    }

    /** An event that was taken into this one because it was in the same place (6-2), and why, as text. */
    data class MergedEvent(
        val type: EventType,
        val reason: String
    )

    /** How many photos each of the three ways of being related brought in (6-4). */
    data class RelatedBy(
        val clue: Int,
        val person: Int,
        val sameScene: Int
    )

    /** How a general bundle was cut, one rule as text each (for example `rule3_slot_change at 15:40 gap 4h10m`). */
    data class General(
        val rules: List<String>,
        val segments: List<ActivitySegment>,
        val activity: ActivityCode?
    )

    /** A stretch of one activity inside a general bundle (7-5). */
    data class ActivitySegment(
        val activity: ActivityCode,
        val range: TimeRange
    )

    /** How the photos of the card were chosen (8-3): the number, who took which place and why, and rules given up. */
    data class Selection(
        val cardSize: Int,
        val picked: List<Pick>,
        val relaxed: List<String>
    )

    /** A chosen photo and the rule that chose it, as text (for example `P3_clue`, `P4_member`). */
    data class Pick(
        val photoId: Long,
        val by: String
    )
}
