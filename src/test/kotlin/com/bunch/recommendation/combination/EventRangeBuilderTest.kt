package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.EventTestSupport.clued
import com.bunch.recommendation.combination.EventTestSupport.friends
import com.bunch.recommendation.combination.EventTestSupport.ready
import com.bunch.recommendation.combination.PreparationTestSupport.photo
import com.bunch.recommendation.domain.combination.EventClueCode.AIRPORT_GATE
import com.bunch.recommendation.domain.combination.EventClueCode.CANDLE_WHOLE_CAKE
import com.bunch.recommendation.domain.combination.EventClueCode.CONCERT_STAGE
import com.bunch.recommendation.domain.combination.EventClueCode.GRAD_CAP
import com.bunch.recommendation.domain.combination.EventClueCode.ROLLER_COASTER
import com.bunch.recommendation.domain.combination.EventEstablisher
import com.bunch.recommendation.domain.combination.EventPlaceMerger
import com.bunch.recommendation.domain.combination.EventRangeBuilder
import com.bunch.recommendation.domain.combination.EventType
import com.bunch.recommendation.domain.combination.RecoPhoto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.LocalTime

/** The photos of the time of an event (recommended bundle specification 6-3). */
class EventRangeBuilderTest {
    private fun ranged(vararg photos: RecoPhoto): List<EventRangeBuilder.Ranged> {
        val day = ready(photos.toList())
        val events = EventPlaceMerger().merge(EventEstablisher().establish(day), day)
        return EventRangeBuilder().build(day, events)
    }

    private fun ids(ranged: EventRangeBuilder.Ranged) = ranged.photos.map { it.photoId }

    private fun cake(id: Long, time: String) = clued(id, time, 1, CANDLE_WHOLE_CAKE, person = true)

    private fun at(minutesAfterEight: Long): String = LocalTime.of(20, 0).plusMinutes(minutesAfterEight).toString()

    @ParameterizedTest
    @CsvSource("119,true", "120,false")
    fun `a short event takes a photo that follows within two hours and not one that follows after two`(
        minutes: Long,
        taken: Boolean
    ) {
        val result = ranged(cake(1, "20:00"), photo(2, time = at(minutes)).copy(analysis = EventTestSupport.analysis()))

        assertThat(ids(result.single()).contains(2L)).isEqualTo(taken)
    }

    @ParameterizedTest
    @CsvSource("239,true", "240,false")
    fun `a long event takes a photo within four hours`(minutes: Long, taken: Boolean) {
        val stage = clued(1, "12:00", 1, CONCERT_STAGE, person = true)
        val later = photo(2, time = LocalTime.of(12, 0).plusMinutes(minutes).toString())

        val result = ranged(stage, later).single()

        assertThat(ids(result).contains(2L)).isEqualTo(taken)
    }

    @Test
    fun `the range goes on as long as every gap is short, before the core and after it`() {
        val before = friends(10, "17:00", 2, step = 90)
        val after = friends(20, "21:30", 3, step = 60)

        val result = ranged(cake(1, "20:00"), *before.toTypedArray(), *after.toTypedArray()).single()

        assertThat(ids(result)).containsAll(listOf(10L, 11L, 1L, 20L, 21L, 22L))
        assertThat(result.range.start).isBefore(result.candidate.core.start)
        assertThat(result.range.end).isAfter(result.candidate.core.end)
    }

    @Test
    fun `the range stops at a gap of two hours, and what is before the gap is not taken either`() {
        val result = ranged(cake(1, "20:00"), photo(2, time = "17:00"), photo(3, time = "18:00")).single()

        assertThat(ids(result)).containsExactly(1L)
    }

    @Test
    fun `the range holds its core`() {
        val result = ranged(cake(1, "20:00"), cake(2, "20:30")).single()

        assertThat(result.range.start).isEqualTo(result.candidate.core.start)
        assertThat(result.range.end).isEqualTo(result.candidate.core.end)
    }

    @Test
    fun `a photo between two events goes to the nearer core, and to the earlier event when they are as near`() {
        val between = listOf(
            friends(10, "11:00", 1).single(),
            friends(11, "11:30", 1).single(),
            friends(12, "12:00", 1).single()
        )

        val cap = clued(1, "10:00", 1, GRAD_CAP, person = true)

        val result = ranged(cap, cake(2, "13:00"), *between.toTypedArray())

        val graduation = result.single { it.candidate.type == EventType.GRADUATION }
        val birthday = result.single { it.candidate.type == EventType.BIRTHDAY }
        assertThat(ids(graduation)).containsExactly(1L, 10L, 11L)
        assertThat(ids(birthday)).containsExactly(12L, 2L)
    }

    @Test
    fun `the range of an event stops where the core of another one begins`() {
        val result = ranged(
            clued(1, "10:00", 1, GRAD_CAP, person = true),
            cake(2, "12:30"),
            photo(3, time = "11:00"),
            photo(4, time = "12:20")
        )

        val birthday = result.single { it.candidate.type == EventType.BIRTHDAY }
        assertThat(ids(birthday)).doesNotContain(1L)
    }

    @Test
    fun `a day event has the whole day, without what the other events took`() {
        val gate = clued(1, "06:00", 1, AIRPORT_GATE, person = true)
        val party = friends(20, "20:10", 3)

        val others = arrayOf(photo(3, time = "12:00"), photo(4, time = "22:20"))

        val result = ranged(gate, cake(2, "20:00"), *party.toTypedArray(), *others)

        val trip = result.single { it.candidate.type == EventType.TRAVEL }
        val birthday = result.single { it.candidate.type == EventType.BIRTHDAY }
        assertThat(ids(birthday)).containsExactly(2L, 20L, 21L, 22L, 4L)
        assertThat(ids(trip)).containsExactly(1L, 3L)
    }

    @Test
    fun `two day events share what is left, a photo going to the event whose core is nearer`() {
        val gate = clued(1, "06:00", 1, AIRPORT_GATE, person = true)
        val coaster = clued(2, "18:00", 2, ROLLER_COASTER, person = true)

        val result = ranged(gate, coaster, photo(3, time = "08:00"), photo(4, time = "16:00"))

        assertThat(ids(result.single { it.candidate.type == EventType.TRAVEL })).containsExactly(1L, 3L)
        assertThat(ids(result.single { it.candidate.type == EventType.THEME_PARK })).containsExactly(4L, 2L)
    }

    @Test
    fun `a photo with a date only has no time and is in no range`() {
        val result = ranged(cake(1, "20:00"), photo(2, time = null)).single()

        assertThat(ids(result)).containsExactly(1L)
    }
}
