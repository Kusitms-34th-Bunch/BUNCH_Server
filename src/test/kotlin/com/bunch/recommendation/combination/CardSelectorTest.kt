package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.CardTestSupport.at
import com.bunch.recommendation.combination.CardTestSupport.ids
import com.bunch.recommendation.combination.CardTestSupport.onCard
import com.bunch.recommendation.combination.CardTestSupport.run
import com.bunch.recommendation.combination.CardTestSupport.select
import com.bunch.recommendation.combination.CardTestSupport.shot
import com.bunch.recommendation.domain.combination.CardCover
import com.bunch.recommendation.domain.combination.CardSelector
import com.bunch.recommendation.domain.combination.PhotoQuality
import com.bunch.recommendation.domain.combination.RecoBundle
import com.bunch.recommendation.domain.combination.RecoPhoto
import com.bunch.recommendation.domain.combination.SourceType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Duration
import java.time.LocalTime
import kotlin.random.Random

/**
 * The card put on a bundle (recommended bundle specification 4, the cases C1 to C8 of 5-3): how many pictures, which,
 * in what order, and the cover.
 */
class CardSelectorTest {
    private val phone = "iPhone15,2"
    private val size = 4032 to 3024

    private fun timeOf(photos: List<RecoPhoto>, id: Long): LocalTime = photos.first { it.photoId == id }.capturedTime!!

    private fun memberOf(photos: List<RecoPhoto>, id: Long): Long =
        photos.first { it.photoId == id }.uploads.first().memberId

    // The most pictures of the card that are in one window of ten minutes.
    private fun mostInAWindow(photos: List<RecoPhoto>, card: List<Long>): Int {
        val times = card.map { timeOf(photos, it) }
        return times.maxOf { start -> times.count { it >= start && it < start.plus(Duration.ofMinutes(10)) } }
    }

    // --- how many ---

    @ParameterizedTest
    @CsvSource("6,6", "7,6", "15,6", "16,8", "25,8", "26,10", "40,10")
    fun `the card has the size of the table, 6 for 6 to 15 pictures, 8 for 16 to 25, 10 from 26`(
        count: Int,
        expected: Int
    ) {
        val result = select(run(1, count))

        assertThat(onCard(result)).hasSize(expected)
        assertThat(result.trace.selection!!.cardSize).isEqualTo(expected)
    }

    @Test
    fun `a bundle with as many pictures as the card holds puts all of them on the card, in the order of the time`() {
        val photos = run(1, 6).reversed()

        val result = select(photos)

        assertThat(onCard(result)).containsExactly(1L, 2L, 3L, 4L, 5L, 6L)
        assertThat(result.trace.selection!!.picked.map { it.by }).containsOnly(CardSelector.ALL)
    }

    @Test
    fun `the places on the card are from 1, each is used once, and the pictures left have no place`() {
        val result = select(run(1, 20))

        val places = result.photos.mapNotNull { it.cardPosition }
        assertThat(places).containsExactlyInAnyOrderElementsOf((1..8).toList())
        assertThat(result.photos.count { it.cardPosition == null }).isEqualTo(12)
    }

    @Test
    fun `photos that are not usable have no place on the card`() {
        val unusable = listOf(
            shot(90, "10:00", source = SourceType.SCREENSHOT),
            shot(91, "10:05", quality = PhotoQuality.LOW)
        )

        val result = select(run(1, 8) + unusable)

        assertThat(onCard(result)).hasSize(6).doesNotContain(90L, 91L)
        assertThat(result.photos.filter { it.photoId >= 90 }.map { it.cardPosition }).containsOnlyNulls()
    }

    @Test
    fun `a bundle with no usable photo has no card`() {
        val unusable = listOf(
            shot(1, "10:00", source = SourceType.SCREENSHOT),
            shot(2, "10:05", quality = PhotoQuality.LOW)
        )

        val result = select(unusable)

        assertThat(onCard(result)).isEmpty()
        assertThat(result.trace.selection!!.cardSize).isZero()
    }

    // --- 1 and 2: the excluded, the duplicates (C1, C2, C4) ---

    @Test
    fun `C1 a photo that several members saved is one picture on the card`() {
        val copies = listOf(
            shot(100, "10:00:00", member = 1, model = phone, millis = 5, size = size),
            shot(101, "10:00:00", member = 2, model = phone, millis = 5, size = size)
        )

        val result = select(run(1, 6, start = "11:00") + copies)

        assertThat(result.trace.selection!!.cardSize).isEqualTo(6)
        assertThat(onCard(result).count { it == 100L || it == 101L }).isLessThanOrEqualTo(1)
    }

    @Test
    fun `C2 a burst of 20 photos of one member is one picture`() {
        val burst = (0 until 20).map { shot(100L + it, "10:00:00", member = 1, model = phone, millis = it) }

        val result = select(run(1, 7, start = "11:00") + burst)

        assertThat(onCard(result).count { it >= 100 }).isLessThanOrEqualTo(1)
    }

    @Test
    fun `C4 blurred photos are not chosen, the card is made of the rest`() {
        val blurred = (0 until 4).map { shot(90L + it, at(it, "09:00", 3), quality = PhotoQuality.LOW) }

        val result = select(run(1, 8, start = "10:00") + blurred)

        assertThat(onCard(result)).hasSize(6)
        assertThat(onCard(result).filter { it >= 90 }).isEmpty()
    }

    // --- 3: the members (C5) ---

    @Test
    fun `C5 a member of 30 pictures and one with a single picture, the single one is on the card, 9 and 1`() {
        val many = run(1, 30, members = listOf(1))
        val single = shot(100, "12:00", member = 2)

        val result = select(many + single)

        assertThat(onCard(result)).hasSize(10)
        assertThat(onCard(result).count { it == 100L }).isEqualTo(1)
        assertThat(onCard(result).count { it < 100 }).isEqualTo(9)
    }

    @Test
    fun `every member who has a picture gets one, the members with the most pictures first`() {
        val photos = run(1, 20, members = listOf(1)) + run(30, 4, members = listOf(2), start = "11:00") +
            shot(50, "12:00", member = 3)

        val result = select(photos)

        val members = onCard(result).map { memberOf(photos, it) }.toSet()
        assertThat(members).containsExactlyInAnyOrder(1L, 2L, 3L)
        assertThat(result.trace.selection!!.picked.take(3).map { it.by }).containsOnly("P3_member")
    }

    @Test
    fun `at most 2 less than the card of the members are given a picture, the ones with the fewest pictures last`() {
        val crowded = (1L..8L).flatMap { member ->
            (0 until 3).map { shot(member * 10 + it, at(it, "09:00", 20), member = member) }
        }
        val single = shot(100, "09:10", member = 9)

        val result = select(crowded + single)

        val guaranteed = result.trace.selection!!.picked.filter { it.by == "P3_member" }
        assertThat(result.trace.selection!!.cardSize).isEqualTo(8)
        assertThat(guaranteed).hasSize(6)
        assertThat(guaranteed.map { it.photoId }).doesNotContain(100L)
    }

    @Test
    fun `a member has at most 60 percent of the card while the pictures of other members are left`() {
        val photos = run(1, 30, members = listOf(1)) + run(100, 10, members = listOf(2), start = "12:00")

        val result = select(photos)

        assertThat(onCard(result)).hasSize(10)
        assertThat(onCard(result).count { it < 100 }).isLessThanOrEqualTo(6)
        assertThat(onCard(result).count { it >= 100 }).isGreaterThanOrEqualTo(4)
    }

    // --- 4: the make-up (C3, C6, C7) ---

    @Test
    fun `C3 pictures taken in five minutes in front of a cake take at most half of the card`() {
        val cake = run(1, 8, start = "12:00", step = 1)
        val others = run(20, 12, start = "13:00", step = 20)
        val photos = cake + others

        val result = select(photos)

        assertThat(onCard(result)).hasSize(8)
        assertThat(mostInAWindow(photos, onCard(result))).isLessThanOrEqualTo(4)
        assertThat(onCard(result).count { it < 20 }).isLessThanOrEqualTo(4)
    }

    @Test
    fun `C6 a bundle of mostly one scene is not told from its content, but no window of 10 minutes is over half`() {
        val scene = run(1, 7, start = "12:00", step = 1)
        val others = run(20, 3, start = "15:00", step = 30)

        val result = select(scene + others)

        assertThat(onCard(result)).hasSize(6)
        assertThat(onCard(result).count { it < 20 }).isEqualTo(3)
    }

    @Test
    fun `C7 a run of one member and a gathering at the start has a picture of the gathering and far apart ones`() {
        val course = run(1, 30, members = listOf(1), start = "07:00")
        val gathering = shot(100, "07:00", member = 2)
        val photos = course + gathering

        val result = select(photos)
        val card = onCard(result)

        assertThat(card).hasSize(10)
        assertThat(card).contains(100L)
        // The picture of the gathering is there by the 3rd rule, whatever its time; the others are far from each other.
        val times = (card - 100L).map { timeOf(photos, it) }.sorted()
        assertThat(times.zipWithNext { before, after -> Duration.between(before, after).toMinutes() })
            .allMatch { it >= 10 }
    }

    @Test
    fun `there is a picture of a together moment on the card`() {
        val alone = run(1, 12, members = listOf(1), start = "06:00", step = 60)
        val others = run(30, 12, members = listOf(2), start = "06:40", step = 60)
        val pair = listOf(shot(80, "20:00", member = 1), shot(81, "20:10", member = 2))

        val result = select(alone + others + pair)

        assertThat(onCard(result)).containsAnyOf(80L, 81L)
    }

    @Test
    fun `every part of the pictures cut into four by the number has a picture on the card`() {
        val photos = run(1, 20)

        val result = select(photos)

        val card = onCard(result)
        assertThat(card.filter { it in 1L..5L }).isNotEmpty()
        assertThat(card.filter { it in 6L..10L }).isNotEmpty()
        assertThat(card.filter { it in 11L..15L }).isNotEmpty()
        assertThat(card.filter { it in 16L..20L }).isNotEmpty()
    }

    // --- 5: the rest ---

    @Test
    fun `the rest takes pictures that are 10 minutes or more from the chosen ones while there are any`() {
        val photos = run(1, 30)

        val result = select(photos)

        val times = onCard(result).map { timeOf(photos, it) }.sorted()
        assertThat(times.zipWithNext { before, after -> Duration.between(before, after).toMinutes() })
            .allMatch { it >= 10 }
        assertThat(result.trace.selection!!.relaxed).isEmpty()
    }

    @Test
    fun `the card never has more than its size, though the rules above ask for more`() {
        val photos = (1L..9L).flatMap { member ->
            run(member * 100, 3, members = listOf(member), start = at(member.toInt(), "09:00", 7))
        }

        val result = select(photos)

        assertThat(onCard(result).size).isLessThanOrEqualTo(result.trace.selection!!.cardSize)
    }

    // --- order, date only, the cover ---

    @Test
    fun `a photo with a date only is after the photos with a time of the same date on the card`() {
        val result = select(run(1, 5) + shot(99, null))

        assertThat(onCard(result)).containsExactly(1L, 2L, 3L, 4L, 5L, 99L)
    }

    @Test
    fun `a photo with a date only is not chosen while there are enough pictures with a time`() {
        val result = select(run(1, 8) + shot(99, null))

        assertThat(onCard(result)).doesNotContain(99L)
    }

    @Test
    fun `the cover is not the first picture in the order of the time when another is better`() {
        val photos = run(1, 6).map { if (it.photoId == 1L) it.copy(quality = PhotoQuality.NORMAL) else it }

        val result = select(photos)

        assertThat(onCard(result).first()).isEqualTo(1L)
        assertThat(result.coverPhotoId).isNotEqualTo(1L).isIn(onCard(result))
        assertThat(result.trace.selection!!.coverBy).isEqualTo(CardCover.TOGETHER_GOOD)
    }

    @Test
    fun `the bundle keeps what it was, and the trace tells the size, the picks and the cover`() {
        val photos = run(1, 20)

        val result: RecoBundle = select(photos)

        assertThat(result.photos.map { it.photoId }).containsExactlyElementsOf(ids(photos))
        assertThat(result.photos).allMatch { it.role == com.bunch.recommendation.domain.combination.PhotoRole.GENERAL }
        val selection = result.trace.selection!!
        assertThat(selection.picked).hasSize(8)
        assertThat(selection.picked.map { it.photoId }).containsExactlyInAnyOrderElementsOf(onCard(result))
        assertThat(selection.coverBy).isNotNull()
    }

    @Test
    fun `the same photos give the same card and the same cover, whatever the order of the input`() {
        val photos = run(1, 30) + run(100, 12, members = listOf(3), start = "13:00", step = 7)

        val straight = select(photos)
        val shuffled = select(photos.shuffled(Random(7)))

        assertThat(onCard(shuffled)).isEqualTo(onCard(straight))
        assertThat(shuffled.coverPhotoId).isEqualTo(straight.coverPhotoId)
        assertThat(shuffled.trace.selection).isEqualTo(straight.trace.selection)
    }
}
