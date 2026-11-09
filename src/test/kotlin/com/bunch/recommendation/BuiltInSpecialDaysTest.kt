package com.bunch.recommendation

import com.bunch.recommendation.domain.BuiltInSpecialDays
import com.bunch.recommendation.domain.SpecialDay
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class BuiltInSpecialDaysTest {
    private val calendar = BuiltInSpecialDays()

    @Test
    fun `the days that fall on the same date every year are known for any year`() {
        listOf(2026, 2030, 2040).forEach { year ->
            assertThat(calendar.specialDay(LocalDate.of(year, 1, 1))).isEqualTo(SpecialDay.NEW_YEAR)
            assertThat(calendar.specialDay(LocalDate.of(year, 5, 5))).isEqualTo(SpecialDay.CHILDRENS_DAY)
            assertThat(calendar.specialDay(LocalDate.of(year, 10, 31))).isEqualTo(SpecialDay.HALLOWEEN)
            assertThat(calendar.specialDay(LocalDate.of(year, 11, 11))).isEqualTo(SpecialDay.PEPERO)
            assertThat(calendar.specialDay(LocalDate.of(year, 12, 24))).isEqualTo(SpecialDay.CHRISTMAS)
            assertThat(calendar.specialDay(LocalDate.of(year, 12, 25))).isEqualTo(SpecialDay.CHRISTMAS)
            assertThat(calendar.specialDay(LocalDate.of(year, 12, 31))).isEqualTo(SpecialDay.NEW_YEARS_EVE)
        }
    }

    @Test
    fun `the lunar holidays of 2026 are the days of the official calendar`() {
        assertThat(days(2026, 2, 16, 17, 18)).containsOnly(SpecialDay.SEOLLAL)
        assertThat(days(2026, 9, 24, 25, 26)).containsOnly(SpecialDay.CHUSEOK)
        assertThat(calendar.specialDay(LocalDate.of(2026, 2, 15))).isNull()
        assertThat(calendar.specialDay(LocalDate.of(2026, 2, 19))).isNull()
        assertThat(calendar.specialDay(LocalDate.of(2026, 9, 23))).isNull()
        assertThat(calendar.specialDay(LocalDate.of(2026, 9, 27))).isNull()
    }

    @Test
    fun `the lunar holidays of 2027 are the days of the official calendar`() {
        assertThat(days(2027, 2, 6, 7, 8, 9)).containsOnly(SpecialDay.SEOLLAL)
        assertThat(days(2027, 9, 14, 15, 16)).containsOnly(SpecialDay.CHUSEOK)
        assertThat(calendar.specialDay(LocalDate.of(2027, 2, 10))).isNull()
        assertThat(calendar.specialDay(LocalDate.of(2027, 9, 17))).isNull()
    }

    @Test
    fun `the other holidays and the substitute holidays of the official calendar are holidays`() {
        val holidays2026 = listOf(3 to 1, 3 to 2, 5 to 24, 5 to 25, 6 to 6, 8 to 15, 8 to 17, 10 to 3, 10 to 5, 10 to 9)
        holidays2026.forEach { (month, day) ->
            assertThat(calendar.specialDay(LocalDate.of(2026, month, day))).isEqualTo(SpecialDay.OTHER_HOLIDAY)
        }
        assertThat(calendar.specialDay(LocalDate.of(2027, 5, 3))).isEqualTo(SpecialDay.OTHER_HOLIDAY)
        assertThat(calendar.specialDay(LocalDate.of(2027, 7, 19))).isEqualTo(SpecialDay.OTHER_HOLIDAY)
        assertThat(calendar.specialDay(LocalDate.of(2027, 10, 11))).isEqualTo(SpecialDay.OTHER_HOLIDAY)
        assertThat(calendar.specialDay(LocalDate.of(2027, 12, 27))).isEqualTo(SpecialDay.OTHER_HOLIDAY)
    }

    @Test
    fun `an ordinary day is not a special day`() {
        assertThat(calendar.specialDay(LocalDate.of(2026, 10, 20))).isNull()
        assertThat(calendar.specialDay(LocalDate.of(2027, 4, 14))).isNull()
    }

    @Test
    fun `a year that is not in the table has only the fixed days`() {
        // 설날 of 2028 is January 26 to 28 but the 2028 calendar was not checked, so it is not known yet.
        assertThat(calendar.specialDay(LocalDate.of(2028, 1, 27))).isNull()
        assertThat(calendar.specialDay(LocalDate.of(2028, 1, 1))).isEqualTo(SpecialDay.NEW_YEAR)
        assertThat(calendar.specialDay(LocalDate.of(2028, 12, 25))).isEqualTo(SpecialDay.CHRISTMAS)
    }

    private fun days(year: Int, month: Int, vararg days: Int): List<SpecialDay?> =
        days.map { calendar.specialDay(LocalDate.of(year, month, it)) }
}
