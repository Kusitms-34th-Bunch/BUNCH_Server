package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.GeneralTestSupport.counter
import com.bunch.recommendation.combination.GeneralTestSupport.day
import com.bunch.recommendation.combination.GeneralTestSupport.segments
import com.bunch.recommendation.combination.GeneralTestSupport.shots
import com.bunch.recommendation.domain.combination.GeneralBigSplitter
import com.bunch.recommendation.domain.combination.GeneralPiece
import com.bunch.recommendation.domain.combination.RecoPhoto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.LocalTime

/** A piece over 40 photos is cut into sub bundles (recommended bundle specification 3-2, the fifth rule). */
class GeneralBigSplitterTest {
    // Clusters of photos, with a gap of 45 minutes (30 for the small step) between a cluster and the next one.
    private fun clusters(count: Int, size: Int = 6, step: Long = 5, spacing: Long = 70): List<RecoPhoto> =
        (0 until count).flatMap { index ->
            shots(
                index * 1000L + 1,
                LocalTime.of(9, 0).plusMinutes(spacing * index).toString(),
                size,
                step = step
            )
        }

    private fun split(photos: List<RecoPhoto>): List<GeneralPiece> {
        val piece = GeneralPiece(segments(photos), emptyList())
        return GeneralBigSplitter().split(piece, counter(day(photos)))
    }

    @Test
    fun `a piece of 40 photos or fewer is not cut`() {
        val result = split(clusters(6))

        assertThat(result).hasSize(1)
        assertThat(result.single().rules).isEmpty()
    }

    @ParameterizedTest
    @CsvSource("5,8,1", "6,7,2")
    fun `a piece of 40 photos is not cut and one of 42 is`(groups: Int, size: Int, parts: Int) {
        assertThat(split(clusters(groups, size = size))).hasSize(parts)
    }

    @Test
    fun `a bigger piece is cut where the two sides are the most alike, when the gaps are as big`() {
        val result = split(clusters(8))

        assertThat(result.map { it.photos.size }).containsExactly(24, 24)
        assertThat(result.first().rules.single()).isEqualTo("rule5_split_big at 13:40 gap 45m")
    }

    @Test
    fun `a part that is still over the limit is cut again`() {
        val result = split(clusters(10, size = 12, step = 2, spacing = 52))

        assertThat(result.sumOf { it.photos.size }).isEqualTo(120)
        assertThat(result.map { it.photos.size }).containsExactly(24, 36, 24, 36)
        assertThat(result.first().rules).hasSize(2)
    }

    @Test
    fun `a piece with no gap to cut at is left whole`() {
        val result = split(shots(1, "09:00", 45, step = 5))

        assertThat(result).hasSize(1)
        assertThat(result.single().photos).hasSize(45)
    }

    @Test
    fun `a boundary where a side is not enough for a bundle is no place to cut`() {
        val small = shots(1, "09:00", 3, step = 5)
        val big = shots(100, "10:00", 40, step = 5)

        val result = split(small + big)

        assertThat(result).hasSize(1)
    }
}
