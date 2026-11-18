package com.bunch.recommendation.combination

import com.bunch.recommendation.domain.SpecialDay
import com.bunch.recommendation.domain.SpecialDayCalendar
import com.bunch.recommendation.domain.combination.CombinationSettings
import com.bunch.recommendation.domain.combination.EventDateWindows
import com.bunch.recommendation.domain.combination.EventType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.LocalDate

/** The days of the year on which one support clue is enough (recommended bundle specification 3-2, 12). */
class EventDateWindowsTest {
    private val windows = EventDateWindows()

    @ParameterizedTest
    @CsvSource(
        "2026-12-23,false",
        "2026-12-24,true",
        "2026-12-25,true",
        "2026-12-26,false",
        "2026-12-30,false",
        "2026-12-31,true",
        "2027-01-01,true",
        "2027-01-02,false",
        "2026-06-15,false"
    )
    fun `the year end is the 24th and 25th of December and the 31st of December and the 1st of January`(
        day: LocalDate,
        expected: Boolean
    ) {
        assertThat(windows.contains(EventType.YEAR_END, day)).isEqualTo(expected)
    }

    @Test
    fun `a holiday is a day that the calendar of the holidays says it is`() {
        val holidays = EventDateWindows { it == LocalDate.of(2026, 9, 25) }

        assertThat(holidays.contains(EventType.HOLIDAY, LocalDate.of(2026, 9, 25))).isTrue()
        assertThat(holidays.contains(EventType.HOLIDAY, LocalDate.of(2026, 9, 26))).isFalse()
        assertThat(windows.contains(EventType.HOLIDAY, LocalDate.of(2026, 9, 25))).isFalse()
    }

    @Test
    fun `the date counts for the year end and the holidays only`() {
        val always = EventDateWindows { true }

        EventType.entries.filter { it != EventType.YEAR_END && it != EventType.HOLIDAY }.forEach {
            assertThat(always.contains(it, LocalDate.of(2026, 12, 24))).isFalse()
        }
    }

    @Test
    fun `the windows of the year end are a setting`() {
        val narrow = EventDateWindows(CombinationSettings(yearEndWindows = emptyList()))

        assertThat(narrow.contains(EventType.YEAR_END, LocalDate.of(2026, 12, 24))).isFalse()
    }

    @Test
    fun `the lunar holidays of the calendar of the special days are the holidays, and the other days are not`() {
        val calendar = object : SpecialDayCalendar {
            override fun specialDay(date: LocalDate): SpecialDay? = when (date) {
                LocalDate.of(2026, 9, 25) -> SpecialDay.CHUSEOK
                LocalDate.of(2027, 2, 7) -> SpecialDay.SEOLLAL
                LocalDate.of(2026, 10, 3) -> SpecialDay.OTHER_HOLIDAY
                else -> null
            }
        }
        val fromCalendar = EventDateWindows.of(CombinationSettings(), calendar)

        assertThat(fromCalendar.contains(EventType.HOLIDAY, LocalDate.of(2026, 9, 25))).isTrue()
        assertThat(fromCalendar.contains(EventType.HOLIDAY, LocalDate.of(2027, 2, 7))).isTrue()
        assertThat(fromCalendar.contains(EventType.HOLIDAY, LocalDate.of(2026, 10, 3))).isFalse()
        assertThat(fromCalendar.contains(EventType.HOLIDAY, LocalDate.of(2026, 6, 1))).isFalse()
    }
}
