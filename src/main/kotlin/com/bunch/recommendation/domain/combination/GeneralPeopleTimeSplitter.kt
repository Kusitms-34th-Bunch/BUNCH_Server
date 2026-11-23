package com.bunch.recommendation.domain.combination

import java.time.Duration

/**
 * The first three rules of the general bundles (recommended bundle specification 3-2). The chunk of a day is cut at the
 * boundaries between two segments, in this order (3-1):
 *
 * 1. The members change: the day is cut first where nobody who uploaded the photos before the boundary uploaded one
 *    after it, the gap is [CombinationSettings.peopleChangeGap] or more and each side has
 *    [CombinationSettings.peopleSplitMinSide] photos or more. It is cut even when a side is not recommended.
 * 2. The part of the day changes: the segment just before and the segment just after are mostly of different parts of
 *    the day, the gap is [CombinationSettings.slotChangeGap] or more and both sides are recommended (3-4).
 * 3. A long gap: the gap is [CombinationSettings.sameSlotGap] or more and both sides are recommended.
 *
 * Then each piece that is left is looked at for the second and the third rule from the boundary with the biggest gap,
 * the earlier one first when the gaps are as big, and what is cut off is looked at again, so that each cut counts the
 * sizes of the two sides again. One boundary gets the first rule that fits and no other. A "side" is everything of the
 * piece before the boundary and everything after it, not the segment next to it.
 */
class GeneralPeopleTimeSplitter(
    private val settings: CombinationSettings = CombinationSettings()
) {
    fun split(
        segments: List<GeneralSegment>,
        rules: List<String>,
        counter: GeneralCounter
    ): List<GeneralPiece> =
        splitByPeople(GeneralPiece(segments, rules), counter)
            .flatMap { splitByTime(it, counter) }

    // The first rule, over the whole piece, from the boundary with the biggest gap.
    private fun splitByPeople(piece: GeneralPiece, counter: GeneralCounter): List<GeneralPiece> {
        val boundary = boundariesByGap(piece).firstOrNull { membersChange(piece, it, counter) }
            ?: return listOf(piece)
        return cut(piece, boundary, PEOPLE).flatMap { splitByPeople(it, counter) }
    }

    // The second and the third rule, from the boundary with the biggest gap.
    private fun splitByTime(piece: GeneralPiece, counter: GeneralCounter): List<GeneralPiece> {
        for (boundary in boundariesByGap(piece)) {
            val rule = timeRuleAt(piece, boundary, counter) ?: continue
            return cut(piece, boundary, rule).flatMap { splitByTime(it, counter) }
        }
        return listOf(piece)
    }

    private fun membersChange(piece: GeneralPiece, boundary: Int, counter: GeneralCounter): Boolean {
        val (left, right) = sides(piece, boundary)
        return gap(piece, boundary) >= settings.peopleChangeGap &&
            counter.photos(left) >= settings.peopleSplitMinSide &&
            counter.photos(right) >= settings.peopleSplitMinSide &&
            counter.uploaders(left).intersect(counter.uploaders(right)).isEmpty()
    }

    // The name of the rule that cuts at the boundary before piece.segments[boundary], or null when none does. The rule
    // is the second when the part of the day changes and the third otherwise;
    // both need two sides that are recommended.
    private fun timeRuleAt(piece: GeneralPiece, boundary: Int, counter: GeneralCounter): String? {
        val gap = gap(piece, boundary)
        val slotChange = piece.segments[boundary - 1].slot != piece.segments[boundary].slot
        val rule = when {
            slotChange && gap >= settings.slotChangeGap -> SLOT
            gap >= settings.sameSlotGap -> LONG_GAP
            else -> return null
        }
        val (left, right) = sides(piece, boundary)
        return rule.takeIf { counter.isRecommendable(left) && counter.isRecommendable(right) }
    }

    // The two pieces, each with the rules of the piece it comes from and the one that cut it.
    private fun cut(piece: GeneralPiece, boundary: Int, rule: String): List<GeneralPiece> {
        val text = GeneralRuleText.rule(rule, piece.segments[boundary].photos.first(), gap(piece, boundary))
        val applied = piece.rules + text
        return listOf(
            GeneralPiece(piece.segments.subList(0, boundary), applied),
            GeneralPiece(piece.segments.subList(boundary, piece.segments.size), applied)
        )
    }

    private fun boundariesByGap(piece: GeneralPiece): List<Int> =
        (1 until piece.segments.size).sortedWith(compareByDescending<Int> { gap(piece, it) }.thenBy { it })

    private fun sides(piece: GeneralPiece, boundary: Int): Pair<List<PreparedPhoto>, List<PreparedPhoto>> =
        piece.segments.subList(0, boundary).flatMap { it.photos } to
            piece.segments.subList(boundary, piece.segments.size).flatMap { it.photos }

    private fun gap(piece: GeneralPiece, boundary: Int): Duration =
        Duration.between(piece.segments[boundary - 1].end, piece.segments[boundary].start)

    companion object {
        const val PEOPLE = "rule1_people_change"
        const val SLOT = "rule2_slot_change"
        const val LONG_GAP = "rule3_long_gap"
    }
}
