package com.bunch.recommendation.domain.combination

import java.time.Instant

/**
 * A stretch of one activity inside a piece (recommended bundle specification 7-5). Its [activity] is the name it keeps
 * when segments of an unknown activity are put to it.
 */
data class GeneralSection(
    val segments: List<GeneralSegment>,
    val activity: ActivityCode
) {
    init {
        require(segments.isNotEmpty()) {
            "a section has at least one segment"
        }
    }

    val photos: List<PreparedPhoto>
        get() = segments.flatMap { it.photos }

    val start: Instant
        get() = segments.first().start

    val end: Instant
        get() = segments.last().end

    companion object {
        /** Two sections side by side as one, with the activity [activity]. */
        fun join(earlier: GeneralSection, later: GeneralSection, activity: ActivityCode) =
            GeneralSection(earlier.segments + later.segments, activity)

        /** Joins the sections side by side that have the same activity, an unknown one excluded (7-5 rule 1 and 4). */
        fun joinSameActivity(sections: List<GeneralSection>): List<GeneralSection> {
            val result = mutableListOf<GeneralSection>()
            for (section in sections) {
                val last = result.lastOrNull()
                val sameActivity = section.activity != ActivityCode.UNKNOWN && last?.activity == section.activity
                if (last != null && sameActivity) {
                    result[result.lastIndex] = join(last, section, last.activity)
                } else {
                    result += section
                }
            }
            return result
        }
    }
}
