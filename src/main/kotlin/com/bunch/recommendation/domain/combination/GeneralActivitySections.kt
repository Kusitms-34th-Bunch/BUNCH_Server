package com.bunch.recommendation.domain.combination

import java.time.Duration

/**
 * The fifth rule of the general bundles (recommended bundle specification 7-5): the segments of a piece become
 * sections of one activity.
 *
 * Segments side by side with the same known activity are joined. A segment of an unknown activity is put to the known
 * section that is nearer in time, the one before when they are as near, and the section keeps its name. A piece with no
 * known activity is one section of an unknown activity. After the unknown segments are put, sections side by side with
 * the same activity are joined again.
 *
 * An unknown segment may have unknown segments between it and the nearest known section. The gap to that section is
 * then the time from the segment to it, and the unknown segments between go to the same side one after the other (the
 * nearer a segment is to a side, the nearer the ones between are to it too, so the sections stay in one piece).
 */
class GeneralActivitySections {
    fun build(segments: List<GeneralSegment>): List<GeneralSection> {
        val sections = mutableListOf<GeneralSection>()
        for (segment in segments) {
            val last = sections.lastOrNull()
            val sameActivity = segment.activity != ActivityCode.UNKNOWN && last?.activity == segment.activity
            if (last != null && sameActivity) {
                sections[sections.lastIndex] = GeneralSection(last.segments + segment, last.activity)
            } else {
                sections += GeneralSection(listOf(segment), segment.activity)
            }
        }
        if (sections.all { it.activity == ActivityCode.UNKNOWN }) {
            return listOf(GeneralSection(segments, ActivityCode.UNKNOWN))
        }
        return GeneralSection.joinSameActivity(attachUnknown(sections))
    }

    private fun attachUnknown(sections: List<GeneralSection>): List<GeneralSection> {
        val known = sections.indices.filter { sections[it].activity != ActivityCode.UNKNOWN }
        val added = known.associateWith { mutableListOf<GeneralSegment>() }
        for (index in sections.indices.filter { sections[it].activity == ActivityCode.UNKNOWN }) {
            val before = known.lastOrNull { it < index }
            val after = known.firstOrNull { it > index }
            val target = when {
                before == null -> after!!
                after == null -> before
                gapAfter(sections, index, after) < gapBefore(sections, before, index) -> after
                else -> before
            }
            added.getValue(target) += sections[index].segments
        }
        return known.map { index ->
            val joined = (sections[index].segments + added.getValue(index)).sortedBy { it.start }
            GeneralSection(joined, sections[index].activity)
        }
    }

    private fun gapBefore(sections: List<GeneralSection>, known: Int, unknown: Int): Duration =
        Duration.between(sections[known].end, sections[unknown].start)

    private fun gapAfter(sections: List<GeneralSection>, unknown: Int, known: Int): Duration =
        Duration.between(sections[unknown].end, sections[known].start)
}
