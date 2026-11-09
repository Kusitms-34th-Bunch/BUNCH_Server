package com.bunch.recommendation.domain

import java.time.LocalDate
import java.time.MonthDay

/**
 * The special days of 4.2.2, kept in the code. The days that fall on the same date every year are always known. The
 * lunar holidays and the substitute holidays move, so they come from a table of the years that were checked against the
 * official announcement of the calendar. A year that is not in the table has only the fixed days.
 *
 * The table has to be extended by hand each year until the holidays are taken from a public calendar.
 * Checked on 2026-11-06 against 「2026년 월력요항」 (한국천문연구원, announced 2025-06-30) and 「2027년 월력요항」
 * (우주항공청, announced 2026-06-29). Not in the table because the announcement does not list them: 2026 노동절,
 * 제헌절 and the local election day of June 3, and 부처님오신날 of 2027.
 *
 * [DB 연동 지점] 연도별 표(YEARLY)는 공휴일 외부 연동 이슈에서 공공데이터포털의 값(DB)으로 대체한다. 그때까지는 해마다
 * 직접 갱신해야 하고, 갱신을 알려 주는 자동 장치는 없다.
 */
class BuiltInSpecialDays : SpecialDayCalendar {
    override fun specialDay(date: LocalDate): SpecialDay? = FIXED[MonthDay.from(date)] ?: YEARLY[date]

    private companion object {
        val FIXED = mapOf(
            MonthDay.of(1, 1) to SpecialDay.NEW_YEAR,
            MonthDay.of(5, 5) to SpecialDay.CHILDRENS_DAY,
            MonthDay.of(10, 31) to SpecialDay.HALLOWEEN,
            MonthDay.of(11, 11) to SpecialDay.PEPERO,
            MonthDay.of(12, 24) to SpecialDay.CHRISTMAS,
            MonthDay.of(12, 25) to SpecialDay.CHRISTMAS,
            MonthDay.of(12, 31) to SpecialDay.NEW_YEARS_EVE
        )

        val YEARLY: Map<LocalDate, SpecialDay> = buildMap {
            days(2026, SpecialDay.SEOLLAL, 2 to 16, 2 to 17, 2 to 18)
            days(2026, SpecialDay.CHUSEOK, 9 to 24, 9 to 25, 9 to 26)
            days(
                2026, SpecialDay.OTHER_HOLIDAY,
                3 to 1, 3 to 2, 5 to 24, 5 to 25, 6 to 6, 8 to 15, 8 to 17, 10 to 3, 10 to 5, 10 to 9
            )

            days(2027, SpecialDay.SEOLLAL, 2 to 6, 2 to 7, 2 to 8, 2 to 9)
            days(2027, SpecialDay.CHUSEOK, 9 to 14, 9 to 15, 9 to 16)
            days(
                2027, SpecialDay.OTHER_HOLIDAY,
                3 to 1, 5 to 1, 5 to 3, 6 to 6, 7 to 17, 7 to 19, 8 to 15, 8 to 16, 10 to 3, 10 to 4,
                10 to 9, 10 to 11, 12 to 27
            )
        }

        private fun MutableMap<LocalDate, SpecialDay>.days(
            year: Int,
            day: SpecialDay,
            vararg monthDays: Pair<Int, Int>
        ) {
            monthDays.forEach { (month, dayOfMonth) -> put(LocalDate.of(year, month, dayOfMonth), day) }
        }
    }
}
