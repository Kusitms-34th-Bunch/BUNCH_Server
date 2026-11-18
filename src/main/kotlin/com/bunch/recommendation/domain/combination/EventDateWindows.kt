package com.bunch.recommendation.domain.combination

import com.bunch.recommendation.domain.SpecialDay
import com.bunch.recommendation.domain.SpecialDayCalendar
import java.time.LocalDate
import java.time.MonthDay

/**
 * The days on which a single support clue is enough for an event (recommended bundle specification 3-2, 6-1): the year
 * end and Christmas, and the lunar holidays. The date counts for these two events only. The year end comes from the
 * settings (12/24 to 12/25 and 12/31 to 1/1); the holidays move with the moon, so whether a date is one is asked of
 * [isHoliday], which in the service is the table of the special days of the calendar of the photo box.
 *
 * The specification says the day of the holiday and one day before and after it. The table has the days of the holiday
 * as the calendar of the country gives them, a substitute holiday included, so a year can have a day more than the
 * specification (the Seollal of 2027 has the 9th of February).
 */
class EventDateWindows(
    private val settings: CombinationSettings = CombinationSettings(),
    private val isHoliday: (LocalDate) -> Boolean = { false }
) {
    fun contains(type: EventType, day: LocalDate): Boolean = when (type) {
        EventType.YEAR_END -> settings.yearEndWindows.any { inWindow(it, MonthDay.from(day)) }
        EventType.HOLIDAY -> isHoliday(day)
        else -> false
    }

    // A window may run into the next year: 12/31 to 1/1.
    private fun inWindow(window: CombinationSettings.MonthDayWindow, day: MonthDay): Boolean =
        if (window.from <= window.to) {
            day >= window.from && day <= window.to
        } else {
            day >= window.from || day <= window.to
        }

    companion object {
        private val LUNAR_HOLIDAYS = setOf(SpecialDay.SEOLLAL, SpecialDay.CHUSEOK)

        /** The windows with the holidays of a calendar of special days. */
        fun of(settings: CombinationSettings, calendar: SpecialDayCalendar): EventDateWindows =
            EventDateWindows(settings) { calendar.specialDay(it) in LUNAR_HOLIDAYS }
    }
}
