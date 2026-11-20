package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.EventTestSupport.clued
import com.bunch.recommendation.combination.EventTestSupport.friends
import com.bunch.recommendation.combination.GeneralTestSupport.active
import com.bunch.recommendation.combination.GeneralTestSupport.day
import com.bunch.recommendation.combination.GeneralTestSupport.ids
import com.bunch.recommendation.combination.PreparationTestSupport.photo
import com.bunch.recommendation.domain.combination.ActivityCode.CAFE
import com.bunch.recommendation.domain.combination.ActivityCode.MEAL
import com.bunch.recommendation.domain.combination.EventClueCode.CANDLE_WHOLE_CAKE
import com.bunch.recommendation.domain.combination.EventFinder
import com.bunch.recommendation.domain.combination.EventResult
import com.bunch.recommendation.domain.combination.EventType
import com.bunch.recommendation.domain.combination.GeneralChunker
import com.bunch.recommendation.domain.combination.RecoPhoto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The day cut at the range of the events that are bundles (recommended bundle specification 7-1, 7-2). */
class GeneralChunkerTest {
    private val chunker = GeneralChunker()

    private fun chunks(photos: List<RecoPhoto>, events: EventResult? = null): List<GeneralChunker.Chunk> {
        val ready = day(photos)
        return chunker.chunks(ready, events ?: EventResult(emptyList(), emptyList()))
    }

    // A birthday at 20:00 with friends until 20:50, and some food photos inside its range that it does not take.
    private fun birthdayDay(): List<RecoPhoto> =
        active(1, "14:00", 6, CAFE) +
            listOf(clued(100, "20:00", 1, CANDLE_WHOLE_CAKE, person = true)) +
            friends(101, "20:10", 5) +
            active(200, "20:25", 2, MEAL) +
            active(300, "23:30", 3, MEAL)

    @Test
    fun `a day with no event is one chunk`() {
        val result = chunks(active(1, "10:00", 4, MEAL) + active(10, "15:00", 4, MEAL))

        assertThat(result).hasSize(1)
        assertThat(result.single().origin).isEqualTo(GeneralChunker.OUTSIDE)
        assertThat(result.single().photos).hasSize(8)
    }

    @Test
    fun `the range of an event cuts the day, and what it did not take is a chunk inside it`() {
        val photos = birthdayDay()
        val events = EventFinder().find(day(photos))

        val result = chunks(photos, events)

        assertThat(events.confirmed.map { it.type }).containsExactly(EventType.BIRTHDAY)
        assertThat(result.map { it.origin })
            .containsExactly(GeneralChunker.OUTSIDE, "${GeneralChunker.LEFTOVER}BIRTHDAY", GeneralChunker.OUTSIDE)
        assertThat(result.map { chunk -> chunk.photos.map { it.photoId } }).containsExactly(
            ids(active(1, "14:00", 6, CAFE)),
            listOf(200L, 201L),
            listOf(300L, 301L, 302L)
        )
    }

    @Test
    fun `what the event took is in no chunk`() {
        val photos = birthdayDay()
        val events = EventFinder().find(day(photos))

        val inChunks = chunks(photos, events).flatMap { chunk -> chunk.photos.map { it.photoId } }

        assertThat(inChunks).doesNotContain(100L, 101L, 105L)
    }

    @Test
    fun `the range of an event that is not a bundle does not cut the day`() {
        val photos = active(1, "10:00", 4, MEAL) + active(10, "12:00", 4, MEAL)
        val failed = EventResult(emptyList(), listOf(EventResult.Failed(EventType.BIRTHDAY, listOf(1L))))

        assertThat(chunks(photos, failed)).hasSize(1)
    }

    @Test
    fun `a photo with a date only is left out`() {
        val photos = active(1, "10:00", 3, MEAL) + photo(50, time = null)

        val result = chunks(photos)

        assertThat(result.single().photos.map { it.photoId }).containsExactly(1L, 2L, 3L)
    }
}
