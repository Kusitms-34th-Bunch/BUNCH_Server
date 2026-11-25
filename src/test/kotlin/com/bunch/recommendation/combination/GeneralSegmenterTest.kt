package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.GeneralTestSupport.segments
import com.bunch.recommendation.combination.GeneralTestSupport.shots
import com.bunch.recommendation.combination.PreparationTestSupport.photo
import com.bunch.recommendation.domain.combination.DayPart
import com.bunch.recommendation.domain.combination.PhotoQuality
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.LocalTime

/** The segments of a chunk: cut at the gaps of 20 minutes or more (recommended bundle specification 3-1). */
class GeneralSegmenterTest {
    @ParameterizedTest
    @CsvSource("19,1", "20,2")
    fun `a gap of 20 minutes cuts and a gap of 19 minutes does not`(minutes: Long, count: Int) {
        val second = LocalTime.of(10, 0).plusMinutes(minutes).toString()

        val result = segments(listOf(photo(1, time = "10:00"), photo(2, time = second)))

        assertThat(result).hasSize(count)
    }

    @Test
    fun `a photo that is not usable still closes a gap`() {
        val photos = listOf(
            photo(1, time = "10:00"),
            photo(2, time = "10:15").copy(quality = PhotoQuality.LOW),
            photo(3, time = "10:30")
        )

        assertThat(segments(photos)).hasSize(1)
    }

    @Test
    fun `a segment knows its part of the day`() {
        val photos = shots(1, "12:00", 3) + shots(10, "13:00", 1)

        val result = segments(photos)

        assertThat(result.map { segment -> segment.photos.map { it.photoId } })
            .containsExactly(listOf(1L, 2L, 3L), listOf(10L))
        assertThat(result.map { it.slot }).containsOnly(DayPart.AFTERNOON)
    }

    @Test
    fun `the part of a segment is the one most of its photos were taken in, the earlier one when they are as many`() {
        val mostlyMorning = shots(1, "11:20", 5)
        val equal = shots(10, "11:40", 4)

        assertThat(segments(mostlyMorning).single().slot).isEqualTo(DayPart.MORNING)
        assertThat(segments(equal).single().slot).isEqualTo(DayPart.MORNING)
    }
}
