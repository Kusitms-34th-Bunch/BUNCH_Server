package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.EventTestSupport.clued
import com.bunch.recommendation.combination.EventTestSupport.friends
import com.bunch.recommendation.combination.EventTestSupport.ready
import com.bunch.recommendation.combination.PreparationTestSupport.scene
import com.bunch.recommendation.domain.combination.EstablishedBy
import com.bunch.recommendation.domain.combination.EventCandidate
import com.bunch.recommendation.domain.combination.EventClueCode.AIRPORT_GATE
import com.bunch.recommendation.domain.combination.EventClueCode.BOUQUET
import com.bunch.recommendation.domain.combination.EventClueCode.CAMPUS_GROUP_PHOTO
import com.bunch.recommendation.domain.combination.EventClueCode.CANDLE_WHOLE_CAKE
import com.bunch.recommendation.domain.combination.EventClueCode.CONCERT_STAGE
import com.bunch.recommendation.domain.combination.EventClueCode.DIPLOMA
import com.bunch.recommendation.domain.combination.EventClueCode.GRAD_CAP
import com.bunch.recommendation.domain.combination.EventClueCode.HOTEL_ROOM
import com.bunch.recommendation.domain.combination.EventClueCode.PARTY_HAT
import com.bunch.recommendation.domain.combination.EventClueCode.TOAST
import com.bunch.recommendation.domain.combination.EventClueCode.WHOLE_CAKE
import com.bunch.recommendation.domain.combination.EventClueCode.XMAS_DECOR
import com.bunch.recommendation.domain.combination.EventEstablisher
import com.bunch.recommendation.domain.combination.EventType
import com.bunch.recommendation.domain.combination.RecoPhoto
import com.bunch.recommendation.domain.combination.RuleTrace.ConfirmSignal
import com.bunch.recommendation.domain.combination.SourceType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.LocalTime

/** The events that stand in a day (recommended bundle specification 6-1). */
class EventEstablisherTest {
    private val establisher = EventEstablisher()

    private fun establish(vararg photos: RecoPhoto): List<EventCandidate> =
        establisher.establish(ready(photos.toList()))

    @Test
    fun `a strong clue with a person as the subject stands, and the person is the sign`() {
        val event = establish(clued(1, "20:00", 1, CANDLE_WHOLE_CAKE, person = true)).single()

        assertThat(event.type).isEqualTo(EventType.BIRTHDAY)
        assertThat(event.establishedBy).isEqualTo(EstablishedBy.STRONG)
        assertThat(event.confirmSignals).containsExactly(ConfirmSignal.PERSON)
        assertThat(event.strongPhotoIds).containsExactly(1L)
    }

    @Test
    fun `a strong clue alone is not enough`() {
        assertThat(establish(clued(1, "20:00", 1, CANDLE_WHOLE_CAKE))).isEmpty()
    }

    @Test
    fun `the same clue in a photo of another group of duplicates confirms it`() {
        val event = establish(
            clued(1, "20:00", 1, CANDLE_WHOLE_CAKE),
            clued(2, "20:30", 2, CANDLE_WHOLE_CAKE)
        ).single()

        assertThat(event.confirmSignals).containsExactly(ConfirmSignal.SAME_CLUE_IN_OTHER_PHOTO)
    }

    @Test
    fun `the same clue in a photo of the same group of duplicates does not`() {
        val twice = listOf(
            clued(1, "20:00:00", 1, CANDLE_WHOLE_CAKE),
            clued(2, "20:00:02", 2, CANDLE_WHOLE_CAKE)
        ).map { it.copy(fingerprint = scene(0)) }

        assertThat(establish(*twice.toTypedArray())).isEmpty()
    }

    @Test
    fun `another clue of the event confirms a strong clue, the clue of the same kind in the same photo does not`() {
        val event = establish(
            clued(1, "20:00", 1, CANDLE_WHOLE_CAKE),
            clued(2, "20:10", 2, PARTY_HAT)
        ).single()

        assertThat(event.confirmSignals).containsExactly(ConfirmSignal.OTHER_CLUE_KINDS)
        assertThat(establish(clued(1, "20:00", 1, CANDLE_WHOLE_CAKE, CANDLE_WHOLE_CAKE))).isEmpty()
    }

    @Test
    fun `the signs of a strong clue are told one by one`() {
        val event = establish(
            clued(1, "20:00", 1, CANDLE_WHOLE_CAKE, person = true),
            clued(2, "20:10", 2, CANDLE_WHOLE_CAKE, PARTY_HAT)
        ).single()

        assertThat(event.confirmSignals).containsExactly(
            ConfirmSignal.SAME_CLUE_IN_OTHER_PHOTO,
            ConfirmSignal.OTHER_CLUE_KINDS,
            ConfirmSignal.PERSON
        )
    }

    @ParameterizedTest
    @CsvSource("119,1", "120,2")
    fun `a short event is cut where its clue photos are two hours apart`(minutesApart: Long, events: Int) {
        val later = LocalTime.of(10, 0).plusMinutes(minutesApart).toString()

        val found = establish(
            clued(1, "10:00", 1, CANDLE_WHOLE_CAKE, person = true),
            clued(2, later, 2, CANDLE_WHOLE_CAKE, person = true)
        )

        assertThat(found).hasSize(events)
    }

    @Test
    fun `a long event is cut at four hours and a day event is not cut`() {
        val concert = { id: Long, time: String -> clued(id, time, 1, CONCERT_STAGE, person = true) }
        assertThat(establish(concert(1, "10:00"), concert(2, "13:59"))).hasSize(1)
        assertThat(establish(concert(1, "10:00"), concert(2, "14:00"))).hasSize(2)

        val trip = establish(
            clued(1, "06:00", 1, AIRPORT_GATE, person = true),
            clued(2, "23:00", 2, HOTEL_ROOM)
        )
        assertThat(trip).hasSize(1)
        assertThat(trip.single().cluePhotoIds).containsExactly(1L, 2L)
    }

    @Test
    fun `two kinds of support clues stand and one kind does not`() {
        val two = establish(clued(1, "20:00", 1, WHOLE_CAKE), clued(2, "20:10", 2, PARTY_HAT)).single()

        assertThat(two.establishedBy).isEqualTo(EstablishedBy.SUPPORT)
        assertThat(two.supportKinds).containsExactly(WHOLE_CAKE, PARTY_HAT)
        assertThat(two.strongPhotoIds).isEmpty()
        assertThat(establish(clued(1, "20:00", 1, WHOLE_CAKE), clued(2, "20:10", 2, WHOLE_CAKE))).isEmpty()
    }

    @Test
    fun `a weak clue counts after a support clue, as one kind whatever their number, and never alone`() {
        val oneWeak = establish(clued(1, "20:00", 1, PARTY_HAT), clued(2, "20:10", 2, BOUQUET))
        val twoWeak = establish(clued(1, "20:00", 1, PARTY_HAT), clued(2, "20:10", 2, BOUQUET, TOAST))
        val onlyWeak = establish(clued(1, "20:00", 1, BOUQUET), clued(2, "20:10", 2, TOAST))

        assertThat(oneWeak.single().establishedBy).isEqualTo(EstablishedBy.SUPPORT)
        assertThat(twoWeak.single().weakKinds).containsExactly(BOUQUET, TOAST)
        assertThat(onlyWeak).isEmpty()
        assertThat(establish(clued(1, "20:00", 1, PARTY_HAT, BOUQUET, TOAST)).single().weakKinds)
            .containsExactly(BOUQUET, TOAST)
    }

    @Test
    fun `a support clue and a day of the year stand for the year end and not for another event`() {
        val christmas = establish(clued(1, "19:00", 1, XMAS_DECOR, date = "2026-12-24")).single()
        val tenth = establish(clued(1, "19:00", 1, XMAS_DECOR, date = "2026-12-10"))
        val birthday = establish(clued(1, "19:00", 1, PARTY_HAT, date = "2026-12-24"))

        assertThat(christmas.establishedBy).isEqualTo(EstablishedBy.DATE_BOOST)
        assertThat(tenth).isEmpty()
        assertThat(birthday).isEmpty()
    }

    @Test
    fun `a clue of the year end is no event when the date and the support clue are not together`() {
        val weakOnly = establish(clued(1, "19:00", 1, TOAST, date = "2026-12-24"))

        assertThat(weakOnly).isEmpty()
    }

    @Test
    fun `the core runs from the first clue photo to the last and the clue photos are the ones of the stretch`() {
        val event = establish(
            clued(2, "20:30", 2, CANDLE_WHOLE_CAKE, person = true),
            clued(1, "20:00", 1, CANDLE_WHOLE_CAKE, person = true),
            *friends(10, "21:00", 3).toTypedArray()
        ).single()

        assertThat(event.cluePhotoIds).containsExactly(1L, 2L)
        assertThat(event.core.start).isBefore(event.core.end)
    }

    @Test
    fun `events of other kinds in a day stand by themselves`() {
        val found = establish(
            clued(1, "10:00", 1, GRAD_CAP, person = true),
            clued(2, "20:00", 1, CANDLE_WHOLE_CAKE, person = true)
        )

        assertThat(found.map { it.type }).containsExactlyInAnyOrder(EventType.GRADUATION, EventType.BIRTHDAY)
    }

    @Test
    fun `a clue below the limit of the confidence and a clue of a photo that is not from the camera are not seen`() {
        val unsure = clued(1, "20:00", 1, CANDLE_WHOLE_CAKE, person = true, confidence = 0.6)
        val saved = clued(2, "20:00", 2, CANDLE_WHOLE_CAKE, person = true)
            .copy(source = SourceType.SAVED)

        assertThat(establish(unsure, saved)).isEmpty()
    }

    @Test
    fun `a diploma and a campus photo in one photo are two kinds`() {
        val event = establish(clued(1, "12:00", 1, DIPLOMA, CAMPUS_GROUP_PHOTO)).single()

        assertThat(event.type).isEqualTo(EventType.GRADUATION)
        assertThat(event.establishedBy).isEqualTo(EstablishedBy.SUPPORT)
    }
}
