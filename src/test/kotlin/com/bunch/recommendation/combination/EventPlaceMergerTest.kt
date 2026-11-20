package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.EventTestSupport.clued
import com.bunch.recommendation.combination.EventTestSupport.ready
import com.bunch.recommendation.domain.combination.EstablishedBy
import com.bunch.recommendation.domain.combination.EventCandidate
import com.bunch.recommendation.domain.combination.EventClueCode.CAMPUS_GROUP_PHOTO
import com.bunch.recommendation.domain.combination.EventClueCode.CANDLE_WHOLE_CAKE
import com.bunch.recommendation.domain.combination.EventClueCode.DIPLOMA
import com.bunch.recommendation.domain.combination.EventClueCode.GRAD_CAP
import com.bunch.recommendation.domain.combination.EventClueCode.WEDDING_DRESS
import com.bunch.recommendation.domain.combination.EventEstablisher
import com.bunch.recommendation.domain.combination.EventPlaceMerger
import com.bunch.recommendation.domain.combination.EventType
import com.bunch.recommendation.domain.combination.RecoPhoto
import com.bunch.recommendation.domain.combination.RuleTrace
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Events in the same place are one event (recommended bundle specification 6-2). */
class EventPlaceMergerTest {
    private fun merged(vararg photos: RecoPhoto): List<EventCandidate> {
        val day = ready(photos.toList())
        return EventPlaceMerger().merge(EventEstablisher().establish(day), day)
    }

    private fun cap(id: Long, time: String) = clued(id, time, 1, GRAD_CAP, person = true)

    private fun cake(id: Long, time: String) = clued(id, time, 2, CANDLE_WHOLE_CAKE, person = true)

    @ParameterizedTest
    @CsvSource("10:00,11:59,1", "10:00,12:00,2")
    fun `events less than two hours apart are one and two hours apart are two`(
        first: String,
        second: String,
        count: Int
    ) {
        assertThat(merged(cap(1, first), cake(2, second))).hasSize(count)
    }

    @Test
    fun `events of which the cores overlap are one`() {
        val result = merged(cap(1, "10:00"), cap(2, "11:50"), cake(3, "11:00"))

        val event = result.single()
        assertThat(event.type).isEqualTo(EventType.GRADUATION)
        assertThat(event.mergedEvents).containsExactly(RuleTrace.MergedEvent(EventType.BIRTHDAY, "core_overlap"))
    }

    @Test
    fun `the one that stays tells which event it took in and how far apart they were`() {
        val event = merged(cap(1, "10:00"), cap(2, "10:30"), cake(3, "11:10")).single()

        assertThat(event.type).isEqualTo(EventType.GRADUATION)
        assertThat(event.mergedEvents).containsExactly(RuleTrace.MergedEvent(EventType.BIRTHDAY, "same_place_gap_40m"))
    }

    @Test
    fun `of two events the one with more clue photos stays`() {
        val event = merged(cake(1, "10:00"), cap(2, "11:00"), cap(3, "11:10")).single()

        assertThat(event.type).isEqualTo(EventType.GRADUATION)
    }

    @Test
    fun `a clue photo of a group of duplicates counts once`() {
        val shots = listOf(cap(2, "11:00:00"), cap(3, "11:00:02")).map { it.copy(fingerprint = 7L) }

        val birthday = listOf(cake(1, "10:00"), clued(4, "10:10", 1, CANDLE_WHOLE_CAKE, person = true))

        val event = merged(*birthday.toTypedArray(), *shots.toTypedArray())

        assertThat(event.single().type).isEqualTo(EventType.BIRTHDAY)
    }

    @Test
    fun `when they have as many a strong clue goes before support clues, whatever came first`() {
        val strong = cake(2, "10:30")
        val support = clued(1, "10:00", 1, DIPLOMA, CAMPUS_GROUP_PHOTO)

        val event = merged(support, strong).single()

        assertThat(event.type).isEqualTo(EventType.BIRTHDAY)
        assertThat(event.establishedBy).isEqualTo(EstablishedBy.STRONG)
        assertThat(event.mergedEvents.map { it.type }).containsExactly(EventType.GRADUATION)
    }

    @Test
    fun `when they are as strong the one that began first stays`() {
        val event = merged(cake(2, "10:30"), cap(1, "10:00")).single()

        assertThat(event.type).isEqualTo(EventType.GRADUATION)
    }

    @Test
    fun `events that follow each other are one place as far as the chain goes`() {
        val chain = merged(cap(1, "10:00"), cake(2, "11:40"), clued(3, "13:20", 1, WEDDING_DRESS, person = true))

        val event = chain.single()
        assertThat(event.type).isEqualTo(EventType.GRADUATION)
        assertThat(event.mergedEvents.map { it.type }).containsExactly(EventType.BIRTHDAY, EventType.WEDDING)
    }

    @Test
    fun `events that are far apart stay and come in the order of their cores`() {
        val result = merged(cake(2, "20:00"), cap(1, "10:00"))

        assertThat(result.map { it.type }).containsExactly(EventType.GRADUATION, EventType.BIRTHDAY)
        assertThat(result.flatMap { it.mergedEvents }).isEmpty()
    }
}
