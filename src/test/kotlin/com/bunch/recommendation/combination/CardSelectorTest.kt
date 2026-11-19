package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.CardTestSupport.at
import com.bunch.recommendation.combination.CardTestSupport.ids
import com.bunch.recommendation.combination.CardTestSupport.onCard
import com.bunch.recommendation.combination.CardTestSupport.run
import com.bunch.recommendation.combination.CardTestSupport.select
import com.bunch.recommendation.combination.CardTestSupport.shot
import com.bunch.recommendation.combination.PreparationTestSupport.scene
import com.bunch.recommendation.domain.combination.BundleKind
import com.bunch.recommendation.domain.combination.CardSelector
import com.bunch.recommendation.domain.combination.CombinationSettings
import com.bunch.recommendation.domain.combination.EventClueCode.CANDLE_WHOLE_CAKE
import com.bunch.recommendation.domain.combination.PeopleCount
import com.bunch.recommendation.domain.combination.PhotoQuality
import com.bunch.recommendation.domain.combination.PhotoSubject
import com.bunch.recommendation.domain.combination.RecoPhoto
import com.bunch.recommendation.domain.combination.SourceType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * The card put on a bundle: the pictures, their order and the cover (recommended bundle specification 8, 13-4, and the
 * photo box specification 4.2.3 and 4.2.8).
 */
class CardSelectorTest {
    private fun share(card: List<Long>, photos: List<RecoPhoto>, member: Long): Int =
        card.count { id -> photos.first { it.photoId == id }.uploads.any { it.memberId == member } }

    private fun subjectCount(card: List<Long>, photos: List<RecoPhoto>, subject: PhotoSubject): Int =
        card.count { id -> photos.first { it.photoId == id }.analysis.subject == subject }

    // --- the size of the card (photo box 4.2.8) ---

    @Test
    fun `a bundle of 10 pictures or fewer puts all of them on the card, in the order of the time`() {
        val photos = run(1, 10).reversed()

        val result = select(photos)

        assertThat(onCard(result)).containsExactlyElementsOf((1L..10L).toList())
        assertThat(result.coverPhotoId).isEqualTo(1L)
        assertThat(result.trace.selection!!.cardSize).isEqualTo(10)
        assertThat(result.trace.selection!!.picked.map { it.by }).containsOnly(CardSelector.ALL)
        assertThat(result.trace.selection!!.relaxed).isEmpty()
    }

    @Test
    fun `a bundle of 11 pictures has 10 chosen, and the one left has no place`() {
        val result = select(run(1, 11))

        assertThat(onCard(result)).hasSize(10)
        assertThat(result.photos.count { it.cardPosition == null }).isEqualTo(1)
        assertThat(result.trace.selection!!.cardSize).isEqualTo(10)
    }

    @Test
    fun `a bundle of 7 pictures puts 7 on the card, and the photos that are not usable no place`() {
        val photos = run(1, 7) + listOf(
            shot(20, "11:00", quality = PhotoQuality.LOW),
            shot(21, "11:05", source = SourceType.SCREENSHOT)
        )

        val result = select(photos)

        assertThat(onCard(result)).hasSize(7)
        assertThat(result.photos.filter { it.photoId in listOf(20L, 21L) }.map { it.cardPosition }).containsOnlyNulls()
        assertThat(result.trace.selection!!.cardSize).isEqualTo(7)
    }

    @Test
    fun `the places on the card are from 1 and each is used once, with the cover at 1`() {
        val result = select(run(1, 30))

        val places = result.photos.mapNotNull { it.cardPosition }
        assertThat(places.sorted()).containsExactlyElementsOf((1..10).toList())
        assertThat(result.photos.first { it.cardPosition == 1 }.photoId).isEqualTo(result.coverPhotoId)
    }

    // --- 13-4 ---

    @Test
    fun `C1 a photo several members uploaded is one picture`() {
        val shared = shot(1, "09:00", members = listOf(1, 2, 3))

        val result = select(listOf(shared) + run(10, 6))

        assertThat(onCard(result).count { it == 1L }).isEqualTo(1)
        assertThat(onCard(result)).hasSize(7)
    }

    @Test
    fun `C2 an event takes at most half of the card from the clue photos, and at least one`() {
        val cakes = (1L..8L).map { shot(it, at(it.toInt()), 1, PhotoSubject.FOOD, clue = CANDLE_WHOLE_CAKE) }
        val others = run(20, 8, subject = PhotoSubject.PERSON, people = PeopleCount.P1)
        val clues = cakes.map { it.photoId }.toSet()

        val card = onCard(select(cakes + others, BundleKind.EVENT, clues))

        assertThat(card.count { it in clues }).isBetween(1, 5)
    }

    @Test
    fun `C3 clue photos that are all blurred are left out, and the card has none`() {
        val blurred = (1L..4L).map { shot(it, at(it.toInt()), 1, PhotoSubject.FOOD, quality = PhotoQuality.LOW) }
        val clues = blurred.map { it.photoId }.toSet()

        val result = select(blurred + run(20, 12), BundleKind.EVENT, clues)

        assertThat(onCard(result).filter { it in clues }).isEmpty()
        assertThat(onCard(result)).hasSize(10)
    }

    @Test
    fun `C4 the limit of the clues does not stop the first clue picture`() {
        val clue = shot(1, "09:00", 1, PhotoSubject.FOOD, clue = CANDLE_WHOLE_CAKE)
        val others = run(10, 14, members = listOf(2))
        val strict = CombinationSettings(clueMaxRatio = 0.05)

        val card = onCard(select(listOf(clue) + others, BundleKind.EVENT, setOf(1L), settings = strict))

        assertThat(card).contains(1L)
    }

    @Test
    fun `C5 a member of 30 pictures and one with a single picture, the single one is on the card, 9 and 1`() {
        val many = run(1, 30, members = listOf(1))
        val single = shot(100, "11:30", member = 2)

        val card = onCard(select(many + single))

        assertThat(card).hasSize(10)
        assertThat(card).contains(100L)
        assertThat(share(card, many + single, member = 1)).isEqualTo(9)
    }

    @Test
    fun `C6 food that is 70 percent of the pictures takes at most half of the card`() {
        val food = run(1, 14, subject = PhotoSubject.FOOD)
        val places = run(100, 6, start = "12:00", subject = PhotoSubject.LANDSCAPE)

        val card = onCard(select(food + places))

        assertThat(subjectCount(card, food + places, PhotoSubject.FOOD)).isLessThanOrEqualTo(5)
    }

    @Test
    fun `C7 a bundle of landscapes of the same course takes the group photo and other scenes`() {
        val course = run(1, 16, subject = PhotoSubject.LANDSCAPE)
        val group = listOf(
            shot(100, "10:30", 1, PhotoSubject.PERSON, PeopleCount.P3_PLUS),
            shot(101, "10:35", 2, PhotoSubject.PERSON, PeopleCount.P3_PLUS)
        )

        val result = select(course + group)

        assertThat(onCard(result).any { it in listOf(100L, 101L) }).isTrue()
        assertThat(result.trace.selection!!.relaxed).contains("subject_cap")
    }

    // --- the members (photo box 4.2.8) ---

    @Test
    fun `every member gets a picture on the card, the one with the fewest pictures first`() {
        val photos = run(1, 30, members = listOf(1)) + run(100, 5, members = listOf(2), start = "12:00") +
            run(200, 3, members = listOf(3), start = "13:00")

        val card = onCard(select(photos))

        assertThat(card).hasSize(10)
        listOf(1L, 2L, 3L).forEach { assertThat(share(card, photos, it)).isGreaterThanOrEqualTo(1) }
    }

    @Test
    fun `7 members with one picture each and a member with many, all 8 are on the card`() {
        val singles = (1L..7L).map { shot(it, at(it.toInt()), member = it + 1) }
        val many = run(100, 20, members = listOf(1), start = "12:00")

        val card = onCard(select(singles + many))

        assertThat(card).containsAll(ids(singles))
        assertThat(card).hasSize(10)
    }

    @Test
    fun `a member has at most 60 percent of the card while the pictures of other members are left`() {
        val photos = run(1, 20, members = listOf(1)) + run(100, 20, members = listOf(2), start = "13:00")

        val card = onCard(select(photos))

        assertThat(share(card, photos, 1)).isBetween(4, 6)
        assertThat(share(card, photos, 2)).isBetween(4, 6)
    }

    // --- the make-up of the card ---

    @Test
    fun `a picture of 3 people or more is on the card when the bundle has one`() {
        val others = run(1, 20, subject = PhotoSubject.FOOD)
        val group = shot(100, "11:40", 1, PhotoSubject.PERSON, PeopleCount.P3_PLUS)

        assertThat(onCard(select(others + group))).contains(100L)
    }

    @Test
    fun `every quarter of the time has a picture on the card`() {
        val blocks = listOf("09:00", "12:00", "15:00", "18:00")
        val photos = blocks.flatMapIndexed { index, start ->
            run(index * 100L + 1, 5, members = listOf(1), start = start)
        }

        val card = onCard(select(photos))

        blocks.indices.forEach { index ->
            val block = (index * 100L + 1)..(index * 100L + 5)
            assertThat(card.any { it in block }).isTrue()
        }
    }

    @Test
    fun `a subject that is not a person takes at most half of the card, and the limit is told when given up`() {
        val food = run(1, 12, subject = PhotoSubject.FOOD)

        val result = select(food)

        assertThat(onCard(result)).hasSize(10)
        assertThat(result.trace.selection!!.relaxed).contains("subject_cap")
    }

    @Test
    fun `the card never has more than its size, though the rules above ask for more`() {
        val members = (1L..8L).flatMap { member ->
            run(member * 100, 3, members = listOf(member), start = at(member.toInt() * 12, "08:00", 5))
        }
        val group = shot(900, "13:00", 1, PhotoSubject.PERSON, PeopleCount.P3_PLUS)
        val clue = shot(901, "14:00", 2, PhotoSubject.PERSON, PeopleCount.P1, clue = CANDLE_WHOLE_CAKE)

        val card = onCard(select(members + group + clue, BundleKind.EVENT, setOf(901L)))

        assertThat(card).hasSize(10)
        assertThat(card).doesNotHaveDuplicates()
    }

    // --- the clues ---

    @Test
    fun `an event takes first the clue picture that shows a person`() {
        val cakes = listOf(
            shot(1, "09:00", 1, PhotoSubject.FOOD, clue = CANDLE_WHOLE_CAKE),
            shot(2, "09:05", 1, PhotoSubject.PERSON, PeopleCount.P3_PLUS, clue = CANDLE_WHOLE_CAKE),
            shot(3, "09:10", 1, PhotoSubject.FOOD, clue = CANDLE_WHOLE_CAKE)
        )

        val result = select(cakes + run(20, 12), BundleKind.EVENT, setOf(1L, 2L, 3L))

        assertThat(result.trace.selection!!.picked.first().photoId).isEqualTo(2L)
        assertThat(result.trace.selection!!.picked.first().by).isEqualTo("P3_clue")
    }

    @Test
    fun `a general bundle takes a picture of an event that was not a bundle`() {
        val photos = run(1, 14)

        val result = select(photos, failed = setOf(7L))

        assertThat(result.trace.selection!!.picked.first().photoId).isEqualTo(7L)
        assertThat(result.trace.selection!!.picked.first().by).isEqualTo("P3_failed_clue")
    }

    // --- the order and the cover (photo box 4.2.3) ---
    @Test
    fun `the cover is the first picture in the order of the time, even when a group photo is on the card`() {
        val group = shot(100, "11:00", 1, PhotoSubject.PERSON, PeopleCount.P3_PLUS)

        val result = select(run(1, 12) + group)

        val card = onCard(result)
        assertThat(card).contains(100L)
        assertThat(card).isSorted()
        assertThat(result.coverPhotoId).isEqualTo(card.first())
        assertThat(result.coverPhotoId).isNotEqualTo(100L)
    }


    @Test
    fun `photos with a date only are put after the photos with a time of the same date`() {
        val dateOnly = listOf(shot(99, null), shot(98, null))

        val card = onCard(select(run(1, 8) + dateOnly))

        assertThat(card).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 98L, 99L)
    }

    // --- the bundle and the day ---

    @Test
    fun `the bundle keeps what it was, and a bundle with no usable photo has no card`() {
        val photos = run(1, 14)

        val result = select(photos)

        assertThat(result.photos.map { it.photoId }).containsExactlyElementsOf(ids(photos))
        assertThat(result.photos.map { it.role }.toSet()).hasSize(1)
        assertThat(result.photoCount).isEqualTo(14)
        assertThat(result.trace.counts.photos).isEqualTo(14)

        val unusable = select((1L..6L).map { shot(it, at(it.toInt()), quality = PhotoQuality.LOW) })
        assertThat(unusable.coverPhotoId).isNull()
        assertThat(unusable.trace.selection!!.cardSize).isZero()
    }

    @Test
    fun `the same photos give the same card, whatever the order of the input`() {
        val photos = run(1, 30, subject = PhotoSubject.FOOD) +
            run(100, 12, start = "12:00", subject = PhotoSubject.LANDSCAPE) +
            listOf(shot(200, "14:00", 1, PhotoSubject.PERSON, PeopleCount.P3_PLUS, fingerprint = scene(2)))

        val straight = select(photos)
        val shuffled = select(photos.shuffled(Random(11)))

        assertThat(onCard(shuffled)).isEqualTo(onCard(straight))
        assertThat(shuffled.trace.selection).isEqualTo(straight.trace.selection)
        assertThat(shuffled.coverPhotoId).isEqualTo(straight.coverPhotoId)
    }
}
