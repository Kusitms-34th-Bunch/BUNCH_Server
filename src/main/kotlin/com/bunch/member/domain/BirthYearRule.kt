package com.bunch.member.domain

import java.time.Year
import java.time.ZoneId

/**
 * The years a member may give as the birth year, at signup and in the profile edit alike (1.4.5, 7.2.6): from 1930
 * to this year minus 14, by the year in Korea. A four-digit number is enough to be in the range.
 */
object BirthYearRule {
    private const val EARLIEST = 1930
    private const val MINIMUM_AGE = 14
    private val ZONE = ZoneId.of("Asia/Seoul")

    fun isValid(year: Int): Boolean = year in EARLIEST..latest()

    private fun latest(): Int = Year.now(ZONE).value - MINIMUM_AGE
}
