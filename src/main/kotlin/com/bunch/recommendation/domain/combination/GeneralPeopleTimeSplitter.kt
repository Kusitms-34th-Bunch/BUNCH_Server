package com.bunch.recommendation.domain.combination

import java.time.Duration

/**
 * The second and the third rule of the general bundles (recommended bundle specification 7-4). A piece is cut at a
 * boundary between two segments when the members change or when the part of the day and the activity change, and what
 * is cut off is looked at again. The boundaries are looked at from the biggest gap, and the earlier one first when the
 * gaps are as big. One boundary gets the first rule that fits and no other.
 *
 * The decision is made from the segment just before and the segment just after the boundary, and the sizes (3 photos,
 * enough for a bundle) are those of everything that would be before and after it.
 */
class GeneralPeopleTimeSplitter(
    private val settings: CombinationSettings = CombinationSettings()
) {
    fun split(
        segments: List<GeneralSegment>,
        rules: List<String>,
        counter: GeneralCounter
    ): List<GeneralPiece> =
        splitPiece(GeneralPiece(segments, rules), counter)

    private fun splitPiece(piece: GeneralPiece, counter: GeneralCounter): List<GeneralPiece> {
        val segments = piece.segments
        val boundaries = (1 until segments.size)
            .sortedWith(compareByDescending<Int> { gap(segments, it) }.thenBy { it })
        for (boundary in boundaries) {
            val rule = ruleAt(segments, boundary, counter) ?: continue
            val applied = piece.rules + rule
            return splitPiece(GeneralPiece(segments.subList(0, boundary), applied), counter) +
                splitPiece(GeneralPiece(segments.subList(boundary, segments.size), applied), counter)
        }
        return listOf(piece)
    }

    // The text of the rule that cuts at the boundary before segments[boundary], or null when none does.
    private fun ruleAt(segments: List<GeneralSegment>, boundary: Int, counter: GeneralCounter): String? {
        val before = segments[boundary - 1]
        val after = segments[boundary]
        val left = segments.subList(0, boundary).flatMap { it.photos }
        val right = segments.subList(boundary, segments.size).flatMap { it.photos }
        val gap = gap(segments, boundary)
        return when {
            membersChange(before, after) &&
                counter.photos(left) >= settings.peopleSplitMinSide &&
                counter.photos(right) >= settings.peopleSplitMinSide ->
                GeneralRuleText.rule(PEOPLE, after.photos.first(), gap)
            slotChange(before, after, gap) && counter.isEnough(left) && counter.isEnough(right) ->
                GeneralRuleText.rule(SLOT, after.photos.first(), gap)
            else -> null
        }
    }

    // Both have members and none of them is in both (2nd rule).
    private fun membersChange(before: GeneralSegment, after: GeneralSegment): Boolean =
        before.uploaders.isNotEmpty() && after.uploaders.isNotEmpty() &&
            before.uploaders.intersect(after.uploaders).isEmpty()

    // The part of the day changes, both activities are known and the gap reaches what keeps the longer one together,
    // a day activity never being cut by time (3rd rule).
    private fun slotChange(before: GeneralSegment, after: GeneralSegment, gap: Duration): Boolean {
        if (before.slot == after.slot) return false
        val unknown = before.activity == ActivityCode.UNKNOWN || after.activity == ActivityCode.UNKNOWN
        if (unknown) return false
        val pace = before.activity.pace!!.longer(after.activity.pace!!)
        val together = settings.continuityOf(pace) ?: return false
        return gap >= together
    }

    private fun gap(segments: List<GeneralSegment>, boundary: Int): Duration =
        Duration.between(segments[boundary - 1].end, segments[boundary].start)

    companion object {
        const val PEOPLE = "rule2_people_change"
        const val SLOT = "rule3_slot_change"
    }
}
