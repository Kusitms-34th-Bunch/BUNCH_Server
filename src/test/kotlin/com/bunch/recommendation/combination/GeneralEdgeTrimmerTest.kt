package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.GeneralTestSupport.counter
import com.bunch.recommendation.combination.GeneralTestSupport.day
import com.bunch.recommendation.combination.GeneralTestSupport.ids
import com.bunch.recommendation.combination.GeneralTestSupport.segments
import com.bunch.recommendation.combination.GeneralTestSupport.shots
import com.bunch.recommendation.domain.combination.GeneralEdgeTrimmer
import com.bunch.recommendation.domain.combination.GeneralPiece
import com.bunch.recommendation.domain.combination.RecoPhoto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The edge trimming (recommended bundle specification 3-3): the first or the last segment that only one member took,
 * with no photo in a moment the members took photos together, is taken off while what is left is recommended.
 */
class GeneralEdgeTrimmerTest {
    private fun trim(photos: List<RecoPhoto>): List<GeneralPiece> =
        GeneralEdgeTrimmer().trim(GeneralPiece(segments(photos), emptyList()), counter(day(photos)))

    private fun idsOf(piece: GeneralPiece) = piece.photos.map { it.photoId }

    @Test
    fun `G18 three photos of a bar that one member took at the end of a dinner are taken off`() {
        val dinner = shots(1, "19:00", 12)
        val bar = shots(100, "22:50", 3, members = listOf(1))

        val result = trim(dinner + bar)

        assertThat(result.map { idsOf(it) }).containsExactly(ids(dinner), ids(bar))
        assertThat(result.first().rules).containsExactly("edge_trim_back at 22:50 gap 2h0m")
    }

    @Test
    fun `G23 three photos on the way to work of one member are taken off the front of a gathering`() {
        val work = shots(1, "08:00", 3, members = listOf(1))
        val meeting = shots(10, "10:00", 20, members = listOf(1, 2, 3), step = 9)

        val result = trim(work + meeting)

        assertThat(result.map { idsOf(it) }).containsExactly(ids(work), ids(meeting))
        assertThat(result.last().rules).containsExactly("edge_trim_front at 10:00 gap 1h40m")
    }

    @Test
    fun `a segment that is in a moment the members took photos together stays`() {
        val first = shots(1, "19:00", 5, members = listOf(1))
        val second = shots(10, "20:05", 6, members = listOf(2), step = 11)

        val result = trim(first + second)

        assertThat(result).hasSize(1)
        assertThat(result.single().rules).isEmpty()
    }

    @Test
    fun `a segment is taken off only when what is left is still recommended`() {
        val work = shots(1, "08:00", 3, members = listOf(1))
        val few = shots(10, "10:00", 5)

        assertThat(trim(work + few)).hasSize(1)
    }

    @Test
    fun `an edge of several segments that one member took is taken off at once, as one piece`() {
        val first = shots(1, "08:00", 3, members = listOf(1))
        val second = shots(10, "09:00", 3, members = listOf(1))
        val meeting = shots(20, "10:30", 10, members = listOf(1, 2, 3))

        val result = trim(first + second + meeting)

        assertThat(result.map { idsOf(it) }).containsExactly(ids(first) + ids(second), ids(meeting))
        assertThat(result.first().rules).containsExactly("edge_trim_front at 10:30 gap 1h10m")
    }

    @Test
    fun `an edge that two members took in turn is not one member alone, and it stays`() {
        val first = shots(1, "08:00", 3, members = listOf(1))
        val second = shots(10, "09:00", 3, members = listOf(2))
        val meeting = shots(20, "10:30", 10, members = listOf(1, 2, 3))

        val result = trim(first + second + meeting)

        assertThat(result).hasSize(1)
        assertThat(result.single().rules).isEmpty()
    }

    @Test
    fun `both the front and the back are taken off`() {
        val work = shots(1, "08:00", 3, members = listOf(1))
        val meeting = shots(10, "10:00", 12)
        val home = shots(100, "13:30", 3, members = listOf(2))

        val result = trim(work + meeting + home)

        assertThat(result.map { idsOf(it) }).containsExactly(ids(work), ids(meeting), ids(home))
    }

    @Test
    fun `a segment in the middle is not taken off, only the edges are looked at`() {
        val before = shots(1, "10:00", 6)
        val alone = shots(10, "11:30", 3, members = listOf(1))
        val after = shots(20, "13:00", 6)

        assertThat(trim(before + alone + after)).hasSize(1)
    }

    @Test
    fun `a piece of one segment is not cut`() {
        val result = trim(shots(1, "10:00", 8))

        assertThat(result).hasSize(1)
        assertThat(result.single().rules).isEmpty()
    }

    @Test
    fun `every photo is in exactly one of the pieces, in the order of the time`() {
        val work = shots(1, "08:00", 3, members = listOf(1))
        val meeting = shots(10, "10:00", 12)
        val home = shots(100, "13:30", 3, members = listOf(2))

        val result = trim(work + meeting + home)

        assertThat(result.flatMap { idsOf(it) }).containsExactlyElementsOf(ids(work + meeting + home))
    }
}
