package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.GeneralTestSupport.active
import com.bunch.recommendation.combination.GeneralTestSupport.counter
import com.bunch.recommendation.combination.GeneralTestSupport.day
import com.bunch.recommendation.domain.combination.ActivityCode.MEAL
import com.bunch.recommendation.domain.combination.ActivityCode.SHOPPING
import com.bunch.recommendation.domain.combination.ActivityCode.SIGHTSEEING
import com.bunch.recommendation.domain.combination.GeneralPeopleTimeSplitter
import com.bunch.recommendation.domain.combination.GeneralPiece
import com.bunch.recommendation.domain.combination.GeneralSegmenter
import com.bunch.recommendation.domain.combination.RecoPhoto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.LocalTime

/** The pieces cut where the members change, or the part of the day and the activity do (specification 7-4). */
class GeneralPeopleTimeSplitterTest {
    private fun pieces(photos: List<RecoPhoto>): List<GeneralPiece> {
        val ready = day(photos)
        val counter = counter(ready)
        val segments = GeneralSegmenter().segment(ready.photos, counter)
        return GeneralPeopleTimeSplitter().split(segments, emptyList(), counter)
    }

    private fun ids(piece: GeneralPiece) = piece.photos.map { it.photoId }

    @Test
    fun `members that are not the same on both sides cut, when each side has 3 photos`() {
        val left = active(1, "10:00", 3, null, members = listOf(1))
        val right = active(10, "10:50", 3, null, members = listOf(2))

        val result = pieces(left + right)

        assertThat(result.map { ids(it) }).containsExactly(listOf(1L, 2L, 3L), listOf(10L, 11L, 12L))
        assertThat(result.first().rules).containsExactly("rule2_people_change at 10:50 gap 30m")
    }

    @Test
    fun `a side of 2 photos does not make the members count, and a member in both sides does not either`() {
        val twoOnTheLeft = active(1, "10:00", 2, null, members = listOf(1)) +
            active(10, "10:50", 3, null, members = listOf(2))
        val shared = active(1, "10:00", 3, null, members = listOf(1, 2)) +
            active(10, "10:50", 3, null, members = listOf(2, 3))

        assertThat(pieces(twoOnTheLeft)).hasSize(1)
        assertThat(pieces(shared)).hasSize(1)
    }

    @ParameterizedTest
    @CsvSource("119,1", "120,2")
    fun `two short activities in two parts of the day are cut at a gap of 2 hours and not below`(
        minutes: Long,
        count: Int
    ) {
        val left = active(1, "11:00", 6, MEAL, step = 5)
        val right = active(10, LocalTime.of(11, 25).plusMinutes(minutes).toString(), 6, MEAL, step = 5)

        assertThat(pieces(left + right)).hasSize(count)
    }

    @ParameterizedTest
    @CsvSource("239,1", "240,2")
    fun `a long activity next to a short one is cut at a gap of 4 hours and not below`(minutes: Long, count: Int) {
        val left = active(1, "11:20", 6, SHOPPING, step = 5)
        val right = active(10, LocalTime.of(11, 45).plusMinutes(minutes).toString(), 6, MEAL, step = 5)

        val result = pieces(left + right)

        assertThat(result).hasSize(count)
        if (count == 2) {
            assertThat(result.first().rules.single()).startsWith("rule3_slot_change at 15:45 gap 4h0m")
        }
    }

    @Test
    fun `a day activity is not cut by time`() {
        val left = active(1, "06:00", 6, SIGHTSEEING, step = 5)
        val right = active(10, "16:00", 6, MEAL, step = 5)

        assertThat(pieces(left + right)).hasSize(1)
    }

    @Test
    fun `a side of which the activity is not known is not cut by time`() {
        val left = active(1, "11:00", 6, MEAL, step = 5)
        val right = active(10, "15:00", 6, null, step = 5)

        assertThat(pieces(left + right)).hasSize(1)
    }

    @Test
    fun `a cut needs both sides to be enough for a bundle`() {
        val left = active(1, "11:00", 5, MEAL, step = 5)
        val right = active(10, "15:00", 6, MEAL, step = 5)

        assertThat(pieces(left + right)).hasSize(1)
    }

    @Test
    fun `the boundary with the biggest gap is cut first, and what is cut off is looked at again`() {
        val first = active(1, "10:00", 3, null, members = listOf(1), step = 5)
        val second = active(10, "10:40", 3, null, members = listOf(2), step = 5)
        val third = active(20, "11:50", 3, null, members = listOf(3), step = 5)

        val result = pieces(first + second + third)

        assertThat(result.map { ids(it) })
            .containsExactly(listOf(1L, 2L, 3L), listOf(10L, 11L, 12L), listOf(20L, 21L, 22L))
        assertThat(result[0].rules).containsExactly(
            "rule2_people_change at 11:50 gap 1h0m",
            "rule2_people_change at 10:40 gap 30m"
        )
        assertThat(result[2].rules).containsExactly("rule2_people_change at 11:50 gap 1h0m")
    }
}
