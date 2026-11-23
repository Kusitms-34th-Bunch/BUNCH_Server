package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.GeneralTestSupport.counter
import com.bunch.recommendation.combination.GeneralTestSupport.day
import com.bunch.recommendation.combination.GeneralTestSupport.ids
import com.bunch.recommendation.combination.GeneralTestSupport.shots
import com.bunch.recommendation.domain.combination.GeneralPeopleTimeSplitter
import com.bunch.recommendation.domain.combination.GeneralPiece
import com.bunch.recommendation.domain.combination.GeneralSegmenter
import com.bunch.recommendation.domain.combination.RecoPhoto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.LocalTime

/**
 * The pieces cut where the members change (rule 1), where the part of the day changes (rule 2) and where the gap is
 * long (rule 3) (recommended bundle specification 3-2).
 */
class GeneralPeopleTimeSplitterTest {
    private fun pieces(photos: List<RecoPhoto>): List<GeneralPiece> {
        val ready = day(photos)
        val segments = GeneralSegmenter().segment(ready.photos)
        return GeneralPeopleTimeSplitter().split(segments, emptyList(), counter(ready))
    }

    private fun idsOf(piece: GeneralPiece) = piece.photos.map { it.photoId }

    private fun after(from: String, minutes: Long): String = LocalTime.parse(from).plusMinutes(minutes).toString()

    // --- rule 1: the members change ---

    @ParameterizedTest
    @CsvSource("59,1", "60,2")
    fun `1st rule cuts at a gap of 1 hour and not below`(minutes: Long, count: Int) {
        val left = shots(1, "10:00", 3, members = listOf(1), step = 5)
        val right = shots(10, after("10:10", minutes), 3, members = listOf(2), step = 5)

        val result = pieces(left + right)

        assertThat(result).hasSize(count)
        if (count == 2) {
            assertThat(result.first().rules).containsExactly("rule1_people_change at 11:10 gap 1h0m")
        }
    }

    @Test
    fun `1st rule cuts even when neither side is enough for a bundle`() {
        val left = shots(1, "10:00", 3, members = listOf(1))
        val right = shots(10, "12:00", 3, members = listOf(2))

        val result = pieces(left + right)

        assertThat(result.map { idsOf(it) }).containsExactly(listOf(1L, 2L, 3L), listOf(10L, 11L, 12L))
    }

    @Test
    fun `1st rule needs 3 photos on each side, and a member in both sides stops it`() {
        val twoOnTheLeft = shots(1, "10:00", 2, members = listOf(1)) + shots(10, "12:00", 3, members = listOf(2))
        val twoOnTheRight = shots(1, "10:00", 3, members = listOf(1)) + shots(10, "12:00", 2, members = listOf(2))
        val shared = shots(1, "10:00", 3, members = listOf(1, 2)) + shots(10, "12:00", 3, members = listOf(2, 3))

        assertThat(pieces(twoOnTheLeft)).hasSize(1)
        assertThat(pieces(twoOnTheRight)).hasSize(1)
        assertThat(pieces(shared)).hasSize(1)
    }

    @Test
    fun `1st rule looks at all of each side and not only at the segments next to the boundary`() {
        val first = shots(1, "10:00", 3, members = listOf(1), step = 5)
        val second = shots(10, "11:30", 3, members = listOf(2), step = 5)
        val third = shots(20, "13:00", 3, members = listOf(1), step = 5)

        assertThat(pieces(first + second + third)).hasSize(1)
    }

    @Test
    fun `1st rule cuts from the boundary with the biggest gap, and what is cut off is looked at again`() {
        val first = shots(1, "10:00", 3, members = listOf(1), step = 5)
        val second = shots(10, "11:30", 3, members = listOf(2), step = 5)
        val third = shots(20, "13:50", 3, members = listOf(3), step = 5)

        val result = pieces(first + second + third)

        assertThat(result.map { idsOf(it) })
            .containsExactly(listOf(1L, 2L, 3L), listOf(10L, 11L, 12L), listOf(20L, 21L, 22L))
        assertThat(result[0].rules).containsExactly(
            "rule1_people_change at 13:50 gap 2h10m",
            "rule1_people_change at 11:30 gap 1h20m"
        )
        assertThat(result[2].rules).containsExactly("rule1_people_change at 13:50 gap 2h10m")
    }

    @Test
    fun `the members cut first, and then the other rules look at each piece`() {
        val morning = shots(1, "09:00", 8, step = 10)
        val afternoon = shots(20, "13:30", 6, step = 10)
        val others = shots(40, "16:00", 4, members = listOf(3), step = 5)

        val result = pieces(morning + afternoon + others)

        assertThat(result.map { idsOf(it) }).containsExactly(ids(morning), ids(afternoon), ids(others))
        assertThat(result[0].rules).containsExactly(
            "rule1_people_change at 16:00 gap 1h40m",
            "rule2_slot_change at 13:30 gap 3h20m"
        )
        assertThat(result[1].rules).containsExactly(
            "rule1_people_change at 16:00 gap 1h40m",
            "rule2_slot_change at 13:30 gap 3h20m"
        )
        assertThat(result[2].rules).containsExactly("rule1_people_change at 16:00 gap 1h40m")
    }

    // --- rule 2: the part of the day changes ---

    @ParameterizedTest
    @CsvSource("119,1", "120,2")
    fun `2nd rule cuts at a gap of 2 hours when the part of the day changes, and not below`(minutes: Long, count: Int) {
        val left = shots(1, "11:00", 6, step = 5)
        val right = shots(10, after("11:25", minutes), 6, step = 5)

        val result = pieces(left + right)

        assertThat(result).hasSize(count)
        if (count == 2) {
            assertThat(result.first().rules).containsExactly("rule2_slot_change at 13:25 gap 2h0m")
        }
    }

    @Test
    fun `2nd rule does not cut when the part of the day is the same and the gap is under 4 hours`() {
        val left = shots(1, "06:00", 6, step = 5)
        val right = shots(10, "09:25", 6, step = 5)

        assertThat(pieces(left + right)).hasSize(1)
    }

    // --- rule 3: a long gap ---

    @ParameterizedTest
    @CsvSource("239,1", "240,2")
    fun `3rd rule cuts at a gap of 4 hours in the same part of the day, and not below`(minutes: Long, count: Int) {
        val left = shots(1, "06:00", 6, step = 5)
        val right = shots(10, after("06:25", minutes), 6, step = 5)

        val result = pieces(left + right)

        assertThat(result).hasSize(count)
        if (count == 2) {
            assertThat(result.first().rules).containsExactly("rule3_long_gap at 10:25 gap 4h0m")
        }
    }

    // --- the sides of rules 2 and 3 are recommended ---

    @Test
    fun `a cut needs both sides to be enough for a bundle`() {
        val left = shots(1, "11:00", 5, step = 5)
        val right = shots(10, "15:00", 6, step = 5)

        assertThat(pieces(left + right)).hasSize(1)
    }

    @Test
    fun `a cut needs both sides to have a moment the members took photos together`() {
        val apart = shots(1, "09:00", 5, members = listOf(1), step = 1) + shots(10, "09:40", 1, members = listOf(2))
        val right = shots(100, "13:00", 6, step = 5)

        assertThat(pieces(apart + right)).hasSize(1)
    }
}
