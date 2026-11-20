package com.bunch.recommendation.domain.combination

/**
 * Takes the photos of the range of an event that belong to its bundle, and tells whether they are enough (recommended
 * bundle specification 6-4). A photo of the range is related when
 * - it shows a clue of the event, or
 * - a person is its subject (one person or more).
 * The third way, a photo of the same scene as a clue photo, needs the likeness of what is in the picture, which the
 * fingerprint cannot tell; it is not used until the embedding is (agreed with the planner, 2026-11-18). So an event
 * takes fewer photos than the specification meant, and the photos of the table or of the room of a party are left.
 *
 * Every related photo is in the bundle, a clue photo of low quality too, but only the usable ones are counted, by their
 * groups of duplicates (3-1). The other photos of the range are what is left, for the general bundles to judge by
 * themselves inside the range (7-2).
 */
class EventPhotoCollector(
    private val clues: EventClues = EventClues(),
    private val eligibility: BundleEligibility = BundleEligibility()
) {
    /** The photos of a range, split into the related ones and the ones that are left, and the count of the ways. */
    data class Collected(
        val related: List<PreparedPhoto>,
        val leftover: List<PreparedPhoto>,
        val relatedBy: RuleTrace.RelatedBy,
        val enough: Boolean
    )

    fun collect(ranged: EventRangeBuilder.Ranged, day: DayPreparation.Ready): Collected {
        val type = ranged.candidate.type
        val (byClue, others) = ranged.photos.partition { clues.isCluePhotoOf(it, type) }
        val byPerson = others.filter(clues::isPersonPhoto)
        val related = ranged.photos.filter { it in byClue || it in byPerson }
        return Collected(
            related = related,
            leftover = ranged.photos.filter { it !in related },
            relatedBy = RuleTrace.RelatedBy(clue = byClue.size, person = byPerson.size, sameScene = 0),
            enough = eligibility.isEnough(related, day.duplicateGroups)
        )
    }
}
