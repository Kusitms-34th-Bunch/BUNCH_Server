package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.EventTestSupport.clued
import com.bunch.recommendation.combination.EventTestSupport.friends
import com.bunch.recommendation.combination.EventTestSupport.ready
import com.bunch.recommendation.combination.EventTestSupport.things
import com.bunch.recommendation.domain.combination.EventClueCode.CANDLE_WHOLE_CAKE
import com.bunch.recommendation.domain.combination.EventEstablisher
import com.bunch.recommendation.domain.combination.EventPhotoCollector
import com.bunch.recommendation.domain.combination.EventRangeBuilder
import com.bunch.recommendation.domain.combination.PhotoQuality
import com.bunch.recommendation.domain.combination.PreparedPhoto
import com.bunch.recommendation.domain.combination.RecoPhoto
import com.bunch.recommendation.domain.combination.RuleTrace
import com.bunch.recommendation.domain.combination.SourceType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The photos of an event that belong to its bundle and whether they are enough (recommended bundle spec 6-4). */
class EventPhotoCollectorTest {
    private fun collect(vararg groups: List<RecoPhoto>): EventPhotoCollector.Collected {
        val day = ready(groups.toList().flatten())
        val ranged = EventRangeBuilder().build(day, EventEstablisher().establish(day))
        return EventPhotoCollector().collect(ranged.single(), day)
    }

    private fun cake(id: Long = 1, time: String = "20:00") = clued(id, time, 1, CANDLE_WHOLE_CAKE, person = true)

    private fun ids(photos: List<PreparedPhoto>) = photos.map { it.photoId }

    @Test
    fun `the related photos are the clue photos and the photos of people, and the rest of the range is left`() {
        val party = friends(10, "20:10", 6)
        val street = things(30, "20:25", 2)

        val collected = collect(listOf(cake()), party, street)

        assertThat(ids(collected.related)).containsExactlyInAnyOrder(1L, 10L, 11L, 12L, 13L, 14L, 15L)
        assertThat(ids(collected.leftover)).containsExactly(30L, 31L)
    }

    @Test
    fun `the ways of being related are counted apart, a clue photo of a person once as a clue`() {
        val collected = collect(listOf(cake()), friends(10, "20:10", 6), things(30, "20:25", 2))

        assertThat(collected.relatedBy).isEqualTo(RuleTrace.RelatedBy(clue = 1, person = 6, sameScene = 0))
    }

    @Test
    fun `a photo of the same scene is not a way of being related until the embedding is`() {
        val table = things(30, "20:05", 3)

        val collected = collect(listOf(cake()), friends(10, "20:10", 6), table)

        assertThat(collected.relatedBy.sameScene).isZero()
        assertThat(ids(collected.related)).doesNotContainAnyElementsOf(table.map { it.photoId })
    }

    @Test
    fun `a bundle with six usable photos and two members is enough`() {
        assertThat(collect(listOf(cake()), friends(10, "20:10", 5)).enough).isTrue()
    }

    @Test
    fun `five photos are too few and one member is too few`() {
        assertThat(collect(listOf(cake()), friends(10, "20:10", 4)).enough).isFalse()
        assertThat(collect(listOf(cake()), friends(10, "20:10", 8, listOf(1))).enough).isFalse()
    }

    @Test
    fun `a clue photo of low quality is in the bundle and is not counted`() {
        val blurred = cake().copy(quality = PhotoQuality.LOW)

        val collected = collect(listOf(blurred), friends(10, "20:10", 4))

        assertThat(ids(collected.related)).contains(1L)
        assertThat(collected.enough).isFalse()
    }

    @Test
    fun `a photo of people that was not taken with the camera is in the bundle and is not counted`() {
        val saved = friends(10, "20:10", 5).map { it.copy(source = SourceType.SAVED) }

        val collected = collect(listOf(cake()), saved)

        assertThat(ids(collected.related)).containsAll(saved.map { it.photoId })
        assertThat(collected.enough).isFalse()
    }
}
