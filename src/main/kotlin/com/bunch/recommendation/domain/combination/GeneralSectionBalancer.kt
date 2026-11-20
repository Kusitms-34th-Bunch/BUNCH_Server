package com.bunch.recommendation.domain.combination

import java.time.Duration

/**
 * The sixth rule of the general bundles (recommended bundle specification 7-6): sections of different activities are
 * only kept apart when each of them is enough for a bundle.
 *
 * While some section is not enough and there is more than one section, the one with the fewest photos (the earlier one
 * when they are as many) is joined to the neighbour with the shorter gap (the one before when the gaps are as long).
 * The joined section has the activity of the side with more photos (the earlier side when they are as many), and
 * sections side by side with the same activity are joined again (7-5). What is left is cut only where both sides are
 * enough.
 */
class GeneralSectionBalancer {
    /** The sections that are left and the rules that joined them, as text for the trace. */
    data class Balanced(
        val sections: List<GeneralSection>,
        val rules: List<String>
    )

    fun balance(sections: List<GeneralSection>, counter: GeneralCounter): Balanced {
        var current = sections
        val rules = mutableListOf<String>()
        while (current.size > 1) {
            val weak = current.filter { !counter.isEnough(it.photos) }
            if (weak.isEmpty()) break
            val smallest = weak.minWith(compareBy<GeneralSection>({ counter.photos(it.photos) }, { it.start }))
            val index = current.indexOfFirst { it === smallest }
            val other = neighbour(current, index)
            val (first, second) = if (other < index) other to index else index to other
            val earlier = current[first]
            val later = current[second]
            val activity = if (counter.photos(later.photos) > counter.photos(earlier.photos)) {
                later.activity
            } else {
                earlier.activity
            }
            rules += GeneralRuleText.rule(
                RULE,
                later.photos.first(),
                Duration.between(earlier.end, later.start)
            )
            current = GeneralSection.joinSameActivity(
                current.subList(0, first) + GeneralSection.join(earlier, later, activity) +
                    current.subList(second + 1, current.size)
            )
        }
        return Balanced(current, rules)
    }

    // The neighbour with the shorter gap, the one before when the gaps are as long.
    private fun neighbour(sections: List<GeneralSection>, index: Int): Int {
        if (index == 0) return 1
        if (index == sections.lastIndex) return index - 1
        val before = Duration.between(sections[index - 1].end, sections[index].start)
        val after = Duration.between(sections[index].end, sections[index + 1].start)
        return if (after < before) index + 1 else index - 1
    }

    companion object {
        const val RULE = "rule6_join_section"
    }
}
