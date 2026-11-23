package com.bunch.recommendation.domain.combination

/**
 * The words a title is made of (recommended bundle specification 4, the name of a bundle): the name of each part of the
 * day. They are kept in one place so the table of the specification can be read against it. The names of the events and
 * of the activities are gone with the events and the activities.
 */
object BundleNames {
    fun dayPartName(part: DayPart): String = when (part) {
        DayPart.MORNING -> "오전"
        DayPart.AFTERNOON -> "오후"
        DayPart.NIGHT -> "밤"
    }
}
