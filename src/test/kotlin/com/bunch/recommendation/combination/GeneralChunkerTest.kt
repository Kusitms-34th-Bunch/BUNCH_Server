package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.GeneralTestSupport.active
import com.bunch.recommendation.combination.GeneralTestSupport.day
import com.bunch.recommendation.combination.PreparationTestSupport.photo
import com.bunch.recommendation.domain.combination.ActivityCode.MEAL
import com.bunch.recommendation.domain.combination.GeneralChunker
import com.bunch.recommendation.domain.combination.RecoPhoto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The photos of a day on the time axis, taken as one chunk (recommended bundle specification 7-1). */
class GeneralChunkerTest {
    private val chunker = GeneralChunker()

    private fun chunks(photos: List<RecoPhoto>): List<GeneralChunker.Chunk> = chunker.chunks(day(photos))

    @Test
    fun `a day is one chunk`() {
        val result = chunks(active(1, "10:00", 4, MEAL) + active(10, "15:00", 4, MEAL))

        assertThat(result).hasSize(1)
        assertThat(result.single().origin).isEqualTo(GeneralChunker.DAY)
        assertThat(result.single().photos).hasSize(8)
    }

    @Test
    fun `the photos of a chunk are in the order of the time`() {
        val result = chunks(active(10, "15:00", 2, MEAL) + active(1, "10:00", 2, MEAL))

        assertThat(result.single().photos.map { it.photoId }).containsExactly(1L, 2L, 10L, 11L)
    }

    @Test
    fun `a photo with a date only is left out`() {
        val photos = active(1, "10:00", 3, MEAL) + photo(50, time = null)

        val result = chunks(photos)

        assertThat(result.single().photos.map { it.photoId }).containsExactly(1L, 2L, 3L)
    }

    @Test
    fun `a day with no photo that has a time gives no chunk`() {
        val result = chunks(listOf(photo(50, time = null), photo(51, time = null)))

        assertThat(result).isEmpty()
    }
}
