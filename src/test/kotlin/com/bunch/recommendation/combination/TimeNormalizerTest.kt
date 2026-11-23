package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.PreparationTestSupport.photo
import com.bunch.recommendation.domain.combination.TimeNormalizer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The clocks of a photo and its day (recommended bundle specification 5-2). */
class TimeNormalizerTest {
    private val times = TimeNormalizer()

    @ParameterizedTest
    @CsvSource(
        "2026-10-19,12:00,2026-10-19",
        "2026-10-19,05:00,2026-10-19",
        "2026-10-20,04:59,2026-10-19",
        "2026-10-20,02:00,2026-10-19",
        "2026-10-20,00:00,2026-10-19",
        "2026-10-20,05:00,2026-10-20"
    )
    fun `a day runs from five in the morning to five in the morning of the next day`(
        date: LocalDate,
        time: String,
        expected: LocalDate
    ) {
        val timed = times.start(photo(1, date = date.toString(), time = time))

        assertThat(times.dayKey(timed)).isEqualTo(expected)
    }

    @Test
    fun `a photo with a date only belongs to that date and a photo without a date to no day`() {
        assertThat(times.dayKey(times.start(photo(1, date = "2026-10-20")))).isEqualTo(LocalDate.of(2026, 10, 20))
        assertThat(times.dayKey(times.start(photo(2, date = null)))).isNull()
        assertThat(times.localTime(times.start(photo(2, date = null)))).isNull()
        assertThat(times.instant(times.start(photo(1, date = "2026-10-20")))).isNull()
    }

    @Test
    fun `a photo without an offset is read in the default zone`() {
        val seoul = times.start(photo(1, time = "09:00"))
        val explicit = times.start(photo(2, time = "09:00", offset = ZoneOffset.ofHours(9)))

        assertThat(times.instant(seoul)).isEqualTo(Instant.parse("2026-10-19T00:00:00Z"))
        assertThat(times.instant(explicit)).isEqualTo(Instant.parse("2026-10-19T00:00:00Z"))
    }

    @Test
    fun `members in other time zones have the day by their local clock and the gap by the absolute one`() {
        val seoul = times.start(photo(1, date = "2026-10-19", time = "09:00", offset = ZoneOffset.ofHours(9)))
        val newYork = times.start(photo(2, date = "2026-10-18", time = "19:00", offset = ZoneOffset.ofHours(-5)))

        assertThat(times.instant(seoul)).isEqualTo(times.instant(newYork))
        assertThat(times.dayKey(seoul)).isEqualTo(LocalDate.of(2026, 10, 19))
        assertThat(times.dayKey(newYork)).isEqualTo(LocalDate.of(2026, 10, 18))
    }

    @Test
    fun `the exact time has the milliseconds and is null when the date, the time or the milliseconds is not known`() {
        val withMillis = photo(1, time = "10:00:05", millis = 123)

        assertThat(times.exactInstant(withMillis)).isEqualTo(Instant.parse("2026-10-19T01:00:05.123Z"))
        assertThat(times.exactInstant(photo(2, time = "10:00:05"))).isNull()
        assertThat(times.exactInstant(photo(3, time = null))).isNull()
        assertThat(times.exactInstant(photo(4, date = null, time = null))).isNull()
    }

    @Test
    fun `the exact time is read with the offset of the photo, and zero milliseconds is a value`() {
        val paris = photo(1, time = "10:00:05", offset = ZoneOffset.ofHours(1), millis = 0)

        assertThat(times.exactInstant(paris)).isEqualTo(Instant.parse("2026-10-19T09:00:05Z"))
    }
}
