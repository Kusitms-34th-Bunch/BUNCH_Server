package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.CardTestSupport.candidates
import com.bunch.recommendation.combination.CardTestSupport.run
import com.bunch.recommendation.combination.CardTestSupport.shot
import com.bunch.recommendation.domain.combination.CardQuarters
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** The pictures of a bundle in four parts of the time (recommended bundle specification 8-3). */
class CardQuartersTest {
    private val quarters = CardQuarters()

    @ParameterizedTest
    @CsvSource("12,3/3/3/3", "13,4/3/3/3", "11,3/3/3/2", "14,4/4/3/3")
    fun `the pictures are shared out evenly and the first parts get the one more`(count: Int, sizes: String) {
        val parts = quarters.of(candidates(run(1, count)))

        assertThat(parts.joinToString("/") { it.size.toString() }).isEqualTo(sizes)
    }

    @Test
    fun `the parts follow the time, whatever the order of the input`() {
        val parts = quarters.of(candidates(run(1, 8).reversed()))

        assertThat(parts.map { part -> part.map { it.photoId } })
            .containsExactly(listOf(1L, 2L), listOf(3L, 4L), listOf(5L, 6L), listOf(7L, 8L))
    }

    @Test
    fun `a photo with no time comes last`() {
        val photos = run(1, 7) + shot(99, null)

        val parts = quarters.of(candidates(photos))

        assertThat(parts.last().map { it.photoId }).containsExactly(7L, 99L)
    }
}
