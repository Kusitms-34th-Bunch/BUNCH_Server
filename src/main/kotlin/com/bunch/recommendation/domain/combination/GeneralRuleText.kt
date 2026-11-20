package com.bunch.recommendation.domain.combination

import java.time.Duration
import java.time.format.DateTimeFormatter

/** The text of a rule as the trace shows it, for example `rule3_slot_change at 15:40 gap 4h10m` (11). */
internal object GeneralRuleText {
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

    /** The rule [name] that was applied at the gap that ends at [after], [gap] long. */
    fun rule(name: String, after: PreparedPhoto, gap: Duration): String =
        "$name at ${after.localTime!!.format(CLOCK)} gap ${gapText(gap)}"

    private fun gapText(gap: Duration): String {
        val hours = gap.toHours()
        val minutes = gap.toMinutesPart()
        return if (hours > 0) "${hours}h${minutes}m" else "${minutes}m"
    }
}
