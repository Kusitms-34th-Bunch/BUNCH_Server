package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.CardTestSupport.bundle
import com.bunch.recommendation.combination.CardTestSupport.run
import com.bunch.recommendation.combination.CardTestSupport.shot
import com.bunch.recommendation.combination.EventTestSupport.ready
import com.bunch.recommendation.domain.combination.BundleKind
import com.bunch.recommendation.domain.combination.CardCandidate
import com.bunch.recommendation.domain.combination.CardPool
import com.bunch.recommendation.domain.combination.EventClueCode.CANDLE_WHOLE_CAKE
import com.bunch.recommendation.domain.combination.PeopleCount
import com.bunch.recommendation.domain.combination.PhotoQuality
import com.bunch.recommendation.domain.combination.PhotoSubject
import com.bunch.recommendation.domain.combination.RecoPhoto
import com.bunch.recommendation.domain.combination.SourceType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/** The pictures a card is made of: the usable photos, one for each group of duplicates (specification 8-2, 8-5). */
class CardPoolTest {
    private val pool = CardPool()

    // Duplicates of the new standard (R1): a burst is one member with one device within two seconds, and the same
    // original saved again is the same moment to the millisecond with the same model and size.
    private val phone = "iPhone15,2"
    private val size = 4032 to 3024

    private fun candidates(
        photos: List<RecoPhoto>,
        kind: BundleKind = BundleKind.GENERAL,
        clues: Set<Long> = emptySet(),
        failed: Set<Long> = emptySet()
    ): List<CardCandidate> {
        val day = ready(photos)
        return pool.build(bundle(day, kind, clues), day, failed)
    }

    private fun ids(candidates: List<CardCandidate>) = candidates.map { it.photoId }

    @Test
    fun `a screenshot, a saved image, a photo of low quality and a document are not on the card`() {
        val photos = run(1, 3) + listOf(
            shot(10, "10:00", source = SourceType.SCREENSHOT),
            shot(11, "10:05", source = SourceType.SAVED),
            shot(12, "10:10", quality = PhotoQuality.LOW),
            shot(13, "10:15", subject = PhotoSubject.DOCUMENT)
        )

        assertThat(ids(candidates(photos))).containsExactly(1L, 2L, 3L)
    }

    @Test
    fun `a group of duplicates is one picture, and the better one stands for it`() {
        val shots = listOf(
            shot(1, "10:00:00", model = phone, quality = PhotoQuality.NORMAL),
            shot(2, "10:00:01", model = phone, quality = PhotoQuality.GOOD),
            shot(3, "10:00:02", model = phone, quality = PhotoQuality.NORMAL)
        )

        assertThat(ids(candidates(shots + run(10, 2)))).containsExactly(10L, 11L, 2L)
    }

    @Test
    fun `of two copies as good the one of the member with fewer pictures stands for the group`() {
        val burst = listOf(
            shot(1, "10:00:00", member = 1, model = phone, millis = 5, size = size),
            shot(2, "10:00:00", member = 2, model = phone, millis = 5, size = size)
        )
        val more = run(10, 4, members = listOf(1))

        assertThat(ids(candidates(burst + more))).containsExactly(10L, 11L, 12L, 13L, 2L)
    }

    @Test
    fun `of two copies as good that are of members with as many pictures the one stored first stands`() {
        val burst = listOf(
            shot(5, "10:00:00", member = 1, model = phone, millis = 5, size = size),
            shot(4, "10:00:00", member = 2, model = phone, millis = 5, size = size)
        )

        assertThat(ids(candidates(burst))).containsExactly(4L)
    }

    @Test
    fun `a photo that members uploaded together counts for the one with the fewest pictures`() {
        val shared = shot(1, "10:00", members = listOf(1, 2))
        val others = run(10, 5, members = listOf(1))

        assertThat(candidates(listOf(shared) + others).first { it.photoId == 1L }.shareMember).isEqualTo(2L)
    }

    @Test
    fun `when they have as many it counts for the one who uploaded it first, then for the lower member number`() {
        val early = Instant.parse("2026-10-20T01:00:00Z")
        val late = Instant.parse("2026-10-20T02:00:00Z")
        val first = shot(1, "10:00", members = listOf(1, 2), uploaded = mapOf(1L to late, 2L to early))
        val tie = shot(2, "10:05", members = listOf(4, 3), uploaded = mapOf(3L to early, 4L to early))

        val result = candidates(listOf(first, tie))

        assertThat(result.first { it.photoId == 1L }.shareMember).isEqualTo(2L)
        assertThat(result.first { it.photoId == 2L }.shareMember).isEqualTo(3L)
    }

    @Test
    fun `the pictures of a member are counted by groups, a burst being one picture`() {
        val burst = listOf(
            shot(1, "10:00:00", member = 1, model = phone),
            shot(2, "10:00:01", member = 1, model = phone),
            shot(3, "10:00:02", member = 1, model = phone)
        )
        val shared = shot(10, "11:00", members = listOf(1, 2))
        val own = run(20, 2, members = listOf(2), start = "12:00")

        val result = candidates(burst + shared + own)

        // Member 1 has 2 pictures (the burst and the shared photo) and member 2 has 3, so the shared photo is 1's.
        assertThat(result.first { it.photoId == 10L }.shareMember).isEqualTo(1L)
    }

    @Test
    fun `a picture is a clue when a photo of its group is a clue of the event, or of an event that was not a bundle`() {
        val cakes = listOf(
            shot(1, "10:00:00", clue = CANDLE_WHOLE_CAKE, model = phone, quality = PhotoQuality.NORMAL),
            shot(2, "10:00:01", model = phone, quality = PhotoQuality.GOOD)
        )
        val others = run(10, 2)

        val event = candidates(cakes + others, BundleKind.EVENT, clues = setOf(1L))
        val general = candidates(cakes + others, failed = setOf(10L))

        assertThat(event.filter { it.clue }.map { it.photoId }).containsExactly(2L)
        assertThat(general.filter { it.clue }.map { it.photoId }).containsExactly(10L)
    }

    @Test
    fun `a picture knows if it shows a person, 3 people or more, and its subject`() {
        val photos = listOf(
            shot(1, "10:00", subject = PhotoSubject.PERSON, people = PeopleCount.P3_PLUS),
            shot(2, "10:05", subject = PhotoSubject.FOOD, people = PeopleCount.P0),
            shot(3, "10:10")
        )

        val result = candidates(photos).associateBy { it.photoId }

        assertThat(result.getValue(1L).person).isTrue()
        assertThat(result.getValue(1L).group).isTrue()
        assertThat(result.getValue(2L).person).isFalse()
        assertThat(result.getValue(2L).subject).isEqualTo(PhotoSubject.FOOD)
        assertThat(result.getValue(3L).subject).isNull()
    }
}
