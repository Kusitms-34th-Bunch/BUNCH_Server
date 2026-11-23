package com.bunch.recommendation.domain.combination

import java.time.Duration

/**
 * The edge trimming of the general bundles (recommended bundle specification 3-3). Photos that one member took alone
 * before or after the gathering (the way to work, a lunch alone, the way home) stick to the front or the back of a
 * bundle when the cutting rules join rather than cut. They are taken off, so that they are not on the card.
 *
 * The edge of a piece is the segments at its front, or at its back, that have no photo in a moment the members took
 * photos together. It is taken off when only one member uploaded all of it, and only when what is left is still
 * recommended (3-4). It is the whole edge that has to be of one member and not each segment of it: two members who
 * took photos in turn at the end of a day (G25, a park of A and a park of B some 40 minutes apart) are not one member
 * who was alone. The new edge is looked at again. The front is looked at first; the answer does not depend on it,
 * because what is left can only get smaller and so a piece that cannot lose its front cannot lose its back either.
 *
 * The moments are counted among the photos that are left of the piece, as for any bundle (R2). What is taken off from
 * the front is one piece and what is taken off from the back is another, and each is judged by the rules like any other
 * piece, so every photo is in exactly one piece (3-5).
 */
class GeneralEdgeTrimmer {
    /** The piece cut into the part taken off the front, the part that is left, and the part taken off the back. */
    fun trim(piece: GeneralPiece, counter: GeneralCounter): List<GeneralPiece> {
        val segments = piece.segments
        var first = 0
        var last = segments.size
        val applied = mutableListOf<String>()
        while (last - first >= 2) {
            val left = segments.subList(first, last)
            val together = counter.togetherPhotoIds(left.flatMap { it.photos })
            val front = left.takeWhile { isApart(it, together) }
            val back = left.takeLastWhile { isApart(it, together) }
            if (canTakeOff(front, left.drop(front.size), counter)) {
                applied += rule(FRONT, left[front.size], gap(left[front.size - 1], left[front.size]))
                first += front.size
            } else if (canTakeOff(back, left.dropLast(back.size), counter)) {
                val boundary = left.size - back.size
                applied += rule(BACK, left[boundary], gap(left[boundary - 1], left[boundary]))
                last -= back.size
            } else {
                break
            }
        }
        if (applied.isEmpty()) return listOf(piece)
        val rules = piece.rules + applied
        return listOfNotNull(
            segments.subList(0, first).takeIf { it.isNotEmpty() },
            segments.subList(first, last),
            segments.subList(last, segments.size).takeIf { it.isNotEmpty() }
        ).map { GeneralPiece(it, rules) }
    }

    // None of the photos of the segment is in a moment the members took photos together.
    private fun isApart(segment: GeneralSegment, together: Set<Long>): Boolean =
        segment.photos.none { it.photoId in together }

    // The edge is something and not all of the piece, one member uploaded all of it, and what is left is recommended.
    private fun canTakeOff(edge: List<GeneralSegment>, rest: List<GeneralSegment>, counter: GeneralCounter): Boolean =
        edge.isNotEmpty() && rest.isNotEmpty() &&
            counter.uploaders(edge.flatMap { it.photos }).size == 1 &&
            counter.isRecommendable(rest.flatMap { it.photos })

    private fun gap(before: GeneralSegment, after: GeneralSegment): Duration =
        Duration.between(before.end, after.start)

    // The text is the rule at the boundary: the time is the first photo after the boundary, as for the cutting rules.
    private fun rule(name: String, after: GeneralSegment, gap: Duration): String =
        GeneralRuleText.rule(name, after.photos.first(), gap)

    companion object {
        const val FRONT = "edge_trim_front"
        const val BACK = "edge_trim_back"
    }
}
