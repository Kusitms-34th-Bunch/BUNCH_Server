package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.EventTestSupport.clued
import com.bunch.recommendation.combination.EventTestSupport.friends
import com.bunch.recommendation.combination.EventTestSupport.ready
import com.bunch.recommendation.combination.EventTestSupport.things
import com.bunch.recommendation.domain.combination.EstablishedBy
import com.bunch.recommendation.domain.combination.EventClueCode.AIRPORT_GATE
import com.bunch.recommendation.domain.combination.EventClueCode.BALLOONS
import com.bunch.recommendation.domain.combination.EventClueCode.BOUQUET
import com.bunch.recommendation.domain.combination.EventClueCode.CANDLE_WHOLE_CAKE
import com.bunch.recommendation.domain.combination.EventClueCode.CONCERT_STAGE
import com.bunch.recommendation.domain.combination.EventClueCode.DIPLOMA
import com.bunch.recommendation.domain.combination.EventClueCode.FIREWORKS
import com.bunch.recommendation.domain.combination.EventClueCode.FORMAL_GROUP_PHOTO
import com.bunch.recommendation.domain.combination.EventClueCode.GRAD_CAP
import com.bunch.recommendation.domain.combination.EventClueCode.HANBOK_GROUP_PHOTO
import com.bunch.recommendation.domain.combination.EventClueCode.HOLIDAY_FOOD
import com.bunch.recommendation.domain.combination.EventClueCode.HOTEL_ROOM
import com.bunch.recommendation.domain.combination.EventClueCode.PARTY_HAT
import com.bunch.recommendation.domain.combination.EventClueCode.ROLLER_COASTER
import com.bunch.recommendation.domain.combination.EventClueCode.TOAST
import com.bunch.recommendation.domain.combination.EventClueCode.TOURIST_SCENERY
import com.bunch.recommendation.domain.combination.EventClueCode.WEDDING_DRESS
import com.bunch.recommendation.domain.combination.EventClueCode.WHOLE_CAKE
import com.bunch.recommendation.domain.combination.EventClueCode.XMAS_DECOR
import com.bunch.recommendation.domain.combination.EventClueCode.XMAS_TREE
import com.bunch.recommendation.domain.combination.EventDateWindows
import com.bunch.recommendation.domain.combination.EventFinder
import com.bunch.recommendation.domain.combination.EventResult
import com.bunch.recommendation.domain.combination.EventType
import com.bunch.recommendation.domain.combination.PhotoQuality
import com.bunch.recommendation.domain.combination.PhotoSubject
import com.bunch.recommendation.domain.combination.RecoPhoto
import com.bunch.recommendation.domain.combination.RuleTrace
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** The events of a day (recommended bundle specification 6 and the cases E1 to E30 of 13-1). */
class EventFinderTest {
    private val finder = EventFinder()

    private fun find(vararg photos: List<RecoPhoto>): EventResult = finder.find(ready(photos.toList().flatten()))

    private fun ids(photos: List<RecoPhoto>) = photos.map { it.photoId }

    // The photos of a stage at a concert, one every [step] minutes, taken by two members in turn.
    private fun stage(firstId: Long, from: String, count: Int, step: Long): List<RecoPhoto> = (0 until count).map {
        clued(
            firstId + it,
            LocalTime.parse(from).plusMinutes(step * it).toString(),
            1L + it % 2,
            CONCERT_STAGE,
            subject = PhotoSubject.OBJECT
        )
    }

    private fun nothing(result: EventResult) {
        assertThat(result.confirmed).isEmpty()
        assertThat(result.failed).isEmpty()
    }

    @Test
    fun `E1 a cake with candles and friends around it, and the party of the same place, is a birthday`() {
        val cake = clued(1, "20:00", 1, CANDLE_WHOLE_CAKE, person = true)
        val party = friends(2, "20:10", 14, listOf(1, 2, 3))

        val event = find(listOf(cake), party).confirmed.single()

        assertThat(event.type).isEqualTo(EventType.BIRTHDAY)
        assertThat(event.establishedBy).isEqualTo(EstablishedBy.STRONG)
        assertThat(event.relatedPhotoIds).hasSize(15)
        assertThat(event.strongPhotoIds).containsExactly(1L)
        assertThat(event.confirmSignals).containsExactly(RuleTrace.ConfirmSignal.PERSON)
        assertThat(event.relatedBy).isEqualTo(RuleTrace.RelatedBy(clue = 1, person = 14, sameScene = 0))
    }

    @Test
    fun `E2 a cafe with pieces of cake and no clue is no event`() {
        nothing(find(friends(1, "15:00", 10)))
    }

    @Test
    fun `E3 a whole cake and a party hat, two kinds of support, are an event named by the day`() {
        val clues = listOf(
            clued(1, "20:00", 1, WHOLE_CAKE),
            clued(2, "20:05", 2, PARTY_HAT, person = true)
        )

        val event = find(clues, friends(3, "20:10", 5)).confirmed.single()

        assertThat(event.type).isEqualTo(EventType.BIRTHDAY)
        assertThat(event.establishedBy).isEqualTo(EstablishedBy.SUPPORT)
        assertThat(event.supportKinds).containsExactly(WHOLE_CAKE, PARTY_HAT)
    }

    @Test
    fun `E4 a bouquet alone is no event`() {
        nothing(find(listOf(clued(1, "12:00", 1, BOUQUET, person = true)), friends(2, "12:10", 8)))
    }

    @Test
    fun `E5 a party from five to nine that goes on is one event whatever the part of the day`() {
        val cake = clued(1, "19:00", 1, CANDLE_WHOLE_CAKE, person = true)

        val event = find(listOf(cake), friends(2, "17:00", 9, step = 30)).confirmed.single()

        assertThat(event.relatedPhotoIds).hasSize(10)
        assertThat(event.range.start).isBefore(event.core.start)
        assertThat(event.range.end).isAfter(event.core.end)
    }

    @Test
    fun `E6 a graduation and a dinner two hours later are apart, the dinner is left to the general bundle`() {
        val cap = clued(1, "14:00", 1, GRAD_CAP, person = true)
        val dinner = friends(20, "17:00", 6)

        val event = find(listOf(cap), friends(2, "14:10", 6), dinner).confirmed.single()

        assertThat(event.type).isEqualTo(EventType.GRADUATION)
        assertThat(event.relatedPhotoIds).doesNotContainAnyElementsOf(ids(dinner))
    }

    @Test
    fun `E7 a graduation and a dinner that follows it with a bouquet and a diploma are one event`() {
        val cap = clued(1, "14:00", 1, GRAD_CAP, person = true)
        val dinner = friends(20, "15:30", 6)

        val event = find(listOf(cap), friends(2, "14:10", 6), dinner).confirmed.single()

        assertThat(event.relatedPhotoIds).containsAll(ids(dinner))
    }

    @Test
    fun `E8 street and sky photos inside the time of a party are not in the event and are left`() {
        val cake = clued(1, "20:00", 1, CANDLE_WHOLE_CAKE, person = true)
        val sky = things(30, "20:25", 2, step = 10)

        val event = find(listOf(cake), friends(2, "20:10", 6), sky).confirmed.single()

        assertThat(event.leftoverPhotoIds).containsExactlyInAnyOrderElementsOf(ids(sky))
        assertThat(event.relatedPhotoIds).doesNotContainAnyElementsOf(ids(sky))
    }

    @Test
    fun `E9 a concert and a cake 40 minutes after it are one event, the cake stays in it`() {
        val stage = stage(1, "19:15", 8, step = 15)
        val cake = listOf(
            clued(20, "21:40", 1, CANDLE_WHOLE_CAKE, person = true),
            clued(21, "21:45", 2, CANDLE_WHOLE_CAKE, person = true),
            clued(22, "21:50", 1, CANDLE_WHOLE_CAKE, person = true)
        )

        val result = find(stage, cake)

        val event = result.confirmed.single()
        assertThat(event.type).isEqualTo(EventType.CONCERT)
        assertThat(event.mergedEvents).containsExactly(RuleTrace.MergedEvent(EventType.BIRTHDAY, "same_place_gap_40m"))
        assertThat(event.relatedPhotoIds).containsAll(ids(cake)).containsAll(ids(stage))
        assertThat(result.failed).isEmpty()
    }

    @Test
    fun `E9-1 a cake three hours after the concert is another place, each is an event`() {
        val stage = stage(1, "19:15", 8, step = 15)
        val cake = (0 until 6).map {
            val time = LocalTime.of(0, 5 * it).toString()
            clued(20L + it, time, 1L + it % 2, CANDLE_WHOLE_CAKE, person = true, date = "2026-10-20")
        }

        val result = find(stage, cake)

        assertThat(result.confirmed.map { it.type }).containsExactly(EventType.CONCERT, EventType.BIRTHDAY)
        assertThat(result.confirmed.first().relatedPhotoIds).doesNotContainAnyElementsOf(ids(cake))
    }

    @Test
    fun `E10 the cake is by one member and the party by three, the members are counted for the whole bundle`() {
        val cake = clued(1, "20:00", 1, CANDLE_WHOLE_CAKE, person = true)

        val result = find(listOf(cake), friends(2, "20:10", 8, listOf(1, 2, 3)))

        assertThat(result.confirmed.single().relatedPhotoIds).hasSize(9)
    }

    @Test
    fun `E11 a party that one member alone took is an event that does not become a bundle`() {
        val cake = clued(1, "20:00", 1, CANDLE_WHOLE_CAKE, person = true)

        val result = find(listOf(cake), friends(2, "20:10", 10, listOf(1)))

        assertThat(result.confirmed).isEmpty()
        assertThat(result.failed).containsExactly(EventResult.Failed(EventType.BIRTHDAY, listOf(1L)))
    }

    @Test
    fun `E12 on the 24th of December, a decoration and friends are a year end event by the date`() {
        val decor = clued(1, "19:00", 1, XMAS_DECOR, person = true, date = "2026-12-24")
        val dinner = friends(2, "19:10", 6, date = "2026-12-24")

        val event = find(listOf(decor), dinner).confirmed.single()

        assertThat(event.type).isEqualTo(EventType.YEAR_END)
        assertThat(event.establishedBy).isEqualTo(EstablishedBy.DATE_BOOST)
    }

    @Test
    fun `E13 three cake photos and ten photos of the party are 13 photos`() {
        val cakes = (0 until 3).map { clued(1L + it, "21:0$it", 1L + it % 2, CANDLE_WHOLE_CAKE, person = true) }

        val event = find(cakes, friends(10, "21:20", 10, listOf(1, 2, 3))).confirmed.single()

        assertThat(event.relatedPhotoIds).hasSize(13)
    }

    @Test
    fun `E14 a party of three photos is too small and nothing around it helps`() {
        val cakes = (0 until 3).map { clued(1L + it, "21:0$it", 1L + it % 2, CANDLE_WHOLE_CAKE, person = true) }

        val result = find(cakes)

        assertThat(result.confirmed).isEmpty()
        assertThat(result.failed.single().cluePhotoIds).containsExactly(1L, 2L, 3L)
    }

    @Test
    fun `E15 a dinner and a cake an hour later are one event, the range reaches the dinner`() {
        val dinner = friends(10, "19:00", 12)
        val cakes = listOf(
            clued(1, "21:50", 1, CANDLE_WHOLE_CAKE, person = true),
            clued(2, "21:51", 2, CANDLE_WHOLE_CAKE, person = true),
            clued(3, "21:52", 1, CANDLE_WHOLE_CAKE, person = true)
        )

        val event = find(dinner, cakes).confirmed.single()

        assertThat(event.relatedPhotoIds).hasSize(15).containsAll(ids(dinner))
    }

    @Test
    fun `E15-1 a dinner and a cake three hours later are apart, the cake is too few and goes to the general bundle`() {
        val dinner = friends(10, "19:00", 12)
        val cakes = listOf(
            clued(1, "23:50", 1, CANDLE_WHOLE_CAKE, person = true),
            clued(2, "23:51", 2, CANDLE_WHOLE_CAKE, person = true),
            clued(3, "23:52", 1, CANDLE_WHOLE_CAKE, person = true)
        )

        val result = find(dinner, cakes)

        assertThat(result.confirmed).isEmpty()
        assertThat(result.failed).containsExactly(EventResult.Failed(EventType.BIRTHDAY, listOf(1L, 2L, 3L)))
    }

    @Test
    fun `E16 a cake that is all blurred still makes the event, it is in the bundle and is not counted`() {
        val cakes = (0 until 3).map {
            clued(1L + it, "21:0$it", 1L + it % 2, CANDLE_WHOLE_CAKE).copy(quality = PhotoQuality.LOW)
        }

        val event = find(cakes, friends(10, "21:10", 10)).confirmed.single()

        assertThat(event.establishedBy).isEqualTo(EstablishedBy.STRONG)
        assertThat(event.relatedPhotoIds).containsAll(listOf(1L, 2L, 3L))
    }

    @Test
    fun `E17 a cake with candles at the next table, with nothing to confirm it, is no event`() {
        val result = find(listOf(clued(1, "20:00", 1, CANDLE_WHOLE_CAKE)), friends(2, "20:10", 12))

        nothing(result)
    }

    @Test
    fun `E17-2 a photo with friends at the gate of an airport is a trip, which may be wrong and is checked later`() {
        val gate = clued(1, "08:00", 1, AIRPORT_GATE, person = true)

        val event = find(listOf(gate), friends(2, "08:10", 5)).confirmed.single()

        assertThat(event.type).isEqualTo(EventType.TRAVEL)
    }

    @Test
    fun `E18 a tree of Christmas in a cafe on the 10th of December is no event`() {
        val trees = (0 until 6).map {
            clued(1L + it, "15:0$it", 1L + it % 2, XMAS_TREE, person = true, date = "2026-12-10")
        }

        nothing(find(trees))
    }

    @Test
    fun `E19 a bouquet and a toast, both weak, are no event`() {
        val weak = listOf(clued(1, "20:00", 1, BOUQUET, person = true), clued(2, "20:05", 2, TOAST, person = true))

        nothing(find(weak, friends(3, "20:10", 6)))
    }

    @Test
    fun `E20 a party hat and a bouquet are two kinds, one of them weak behind a support clue`() {
        val clues = listOf(clued(1, "20:00", 1, PARTY_HAT, person = true), clued(2, "20:05", 2, BOUQUET, person = true))

        val event = find(clues, friends(3, "20:10", 5)).confirmed.single()

        assertThat(event.establishedBy).isEqualTo(EstablishedBy.SUPPORT)
        assertThat(event.weakKinds).containsExactly(BOUQUET)
    }

    @Test
    fun `E21 a concert with no photos for three hours in the middle is one event`() {
        val stage = listOf(
            clued(1, "18:30", 1, CONCERT_STAGE, subject = PhotoSubject.OBJECT),
            clued(2, "21:50", 2, CONCERT_STAGE, subject = PhotoSubject.OBJECT)
        )

        val event = find(stage, friends(10, "22:00", 6)).confirmed.single()

        assertThat(event.type).isEqualTo(EventType.CONCERT)
        assertThat(event.relatedPhotoIds).hasSize(8)
    }

    @Test
    fun `E22 a wedding at one, with the group photo at half past three, is one event`() {
        val dress = clued(1, "13:00", 1, WEDDING_DRESS, person = true)
        val group = clued(2, "15:30", 2, FORMAL_GROUP_PHOTO, person = true)

        val event = find(listOf(dress, group), friends(10, "15:40", 5)).confirmed.single()

        assertThat(event.type).isEqualTo(EventType.WEDDING)
        assertThat(event.relatedPhotoIds).hasSize(7)
    }

    @Test
    fun `E23 a trip is the whole day, with the photos of no kind in it left`() {
        val clues = listOf(
            clued(1, "08:00", 1, AIRPORT_GATE, person = true),
            clued(2, "15:00", 2, TOURIST_SCENERY, person = true),
            clued(3, "22:00", 1, HOTEL_ROOM, subject = PhotoSubject.OBJECT)
        )
        val street = things(30, "12:00", 1)

        val event = find(clues, friends(10, "15:10", 4), street).confirmed.single()

        assertThat(event.type).isEqualTo(EventType.TRAVEL)
        assertThat(event.relatedPhotoIds).hasSize(7)
        assertThat(event.leftoverPhotoIds).containsExactlyElementsOf(ids(street))
    }

    @Test
    fun `E24 a bouquet in the morning and balloons at night, eight hours apart, are not two kinds`() {
        val morning = clued(1, "09:00", 1, BOUQUET, person = true)
        val night = clued(2, "17:00", 2, BALLOONS, person = true)

        nothing(find(listOf(morning, night), friends(10, "17:10", 8)))
    }

    @Test
    fun `E27 a roller coaster with friends is a day at an amusement park`() {
        val coaster = clued(1, "11:00", 1, ROLLER_COASTER, person = true)

        val event = find(listOf(coaster), friends(2, "12:00", 6, step = 60)).confirmed.single()

        assertThat(event.type).isEqualTo(EventType.THEME_PARK)
    }

    @Test
    fun `E28 a photo of hanbok at a palace in April is no holiday`() {
        val hanbok = (0 until 6).map {
            clued(1L + it, "15:0$it", 1L + it % 2, HANBOK_GROUP_PHOTO, person = true, date = "2026-04-12")
        }

        nothing(find(hanbok))
    }

    @Test
    fun `E29 hanbok and food of the holiday are two kinds of support and an event without the date`() {
        val clues = listOf(
            clued(1, "12:00", 1, HANBOK_GROUP_PHOTO, person = true, date = "2026-09-25"),
            clued(2, "12:10", 2, HOLIDAY_FOOD, person = true, date = "2026-09-25")
        )

        val event = find(clues, friends(10, "12:20", 5, date = "2026-09-25")).confirmed.single()

        assertThat(event.type).isEqualTo(EventType.HOLIDAY)
        assertThat(event.establishedBy).isEqualTo(EstablishedBy.SUPPORT)
    }

    @Test
    fun `a holiday with one support clue stands by the date of the calendar`() {
        val holiday = EventFinder(windows = EventDateWindows { it == LocalDate.of(2026, 9, 25) })
        val clues = listOf(clued(1, "12:00", 1, HANBOK_GROUP_PHOTO, person = true, date = "2026-09-25"))
        val day = ready(clues + friends(10, "12:10", 6, date = "2026-09-25"))

        val event = holiday.find(day).confirmed.single()

        assertThat(event.establishedBy).isEqualTo(EstablishedBy.DATE_BOOST)
    }

    @Test
    fun `E30 fireworks with friends under them are a festival`() {
        val fireworks = clued(1, "21:00", 1, FIREWORKS, person = true)

        val event = find(listOf(fireworks), friends(2, "21:10", 6)).confirmed.single()

        assertThat(event.type).isEqualTo(EventType.CONCERT)
    }

    @Test
    fun `an event that is not a bundle is taken out and the range of the next one grows over its place`() {
        val cap = clued(1, "18:00", 1, GRAD_CAP, person = true)
        val earlier = friends(10, "16:40", 3)
        val between = friends(15, "19:00", 2, step = 20)
        val cake = clued(2, "20:15", 1, CANDLE_WHOLE_CAKE, person = true)

        val result = find(listOf(cap), earlier, between, listOf(cake), friends(20, "20:25", 6))

        assertThat(result.failed).containsExactly(EventResult.Failed(EventType.GRADUATION, listOf(1L)))
        val event = result.confirmed.single()
        assertThat(event.type).isEqualTo(EventType.BIRTHDAY)
        assertThat(event.relatedPhotoIds).hasSize(13).contains(1L).containsAll(ids(earlier)).containsAll(ids(between))
    }

    @Test
    fun `the events come in the order of their cores and the result is the same whatever the order of the photos`() {
        val photos = listOf(
            clued(1, "10:00", 1, GRAD_CAP, person = true),
            clued(2, "20:00", 1, CANDLE_WHOLE_CAKE, person = true)
        ) + friends(10, "10:10", 6) + friends(20, "20:10", 6)

        val forward = finder.find(ready(photos))
        val reversed = finder.find(ready(photos.reversed()))

        assertThat(forward.confirmed.map { it.type }).containsExactly(EventType.GRADUATION, EventType.BIRTHDAY)
        assertThat(reversed).isEqualTo(forward)
    }

    @Test
    fun `a day without photos or without clues has no event`() {
        nothing(find(friends(1, "10:00", 10)))
    }

    @Test
    fun `DIPLOMA alone, one support clue, is no event and a clue below the confidence limit is not a clue`() {
        val diploma = listOf(clued(1, "12:00", 1, DIPLOMA, person = true))
        val weakSight = listOf(clued(2, "12:10", 1, CANDLE_WHOLE_CAKE, person = true, confidence = 0.69))

        nothing(find(diploma, friends(10, "12:20", 6)))
        nothing(find(weakSight, friends(20, "12:20", 6)))
    }
}
