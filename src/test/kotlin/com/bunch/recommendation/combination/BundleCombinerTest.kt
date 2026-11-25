package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.CombinerTestSupport.bundles
import com.bunch.recommendation.combination.CombinerTestSupport.combine
import com.bunch.recommendation.combination.CombinerTestSupport.meals
import com.bunch.recommendation.combination.CombinerTestSupport.onCard
import com.bunch.recommendation.combination.CombinerTestSupport.photoIds
import com.bunch.recommendation.combination.CombinerTestSupport.shot
import com.bunch.recommendation.combination.PreparationTestSupport.scene
import com.bunch.recommendation.domain.combination.ActivityCode.MEAL
import com.bunch.recommendation.domain.combination.AiStatus
import com.bunch.recommendation.domain.combination.BundleCombiner
import com.bunch.recommendation.domain.combination.BundleKind
import com.bunch.recommendation.domain.combination.DayCombination
import com.bunch.recommendation.domain.combination.PhotoRole
import com.bunch.recommendation.domain.combination.RecoPhoto
import com.bunch.recommendation.domain.combination.SensitiveClueCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.random.Random

/**
 * The bundles of the photos of a bunch, from the photos to the cards (recommended bundle specification 2, 13): a day is
 * made ready, held or blocked, its general bundles are made (there are no event bundles), the photos with a date only
 * are given to a bundle, and the card is chosen for each.
 */
class BundleCombinerTest {
    // --- what a day says (5-6, 13-5) ---

    @Test
    fun `X1 a day in which nothing stands has no bundle`() {
        val day = combine(meals(1, "10:00", 3)).days.single()

        assertThat(day).isEqualTo(DayCombination.Bundled(LocalDate.parse("2026-10-19"), emptyList()))
    }

    @Test
    fun `X6 a day with a photo that is still being analyzed is held and nothing is made of it`() {
        val pending = shot(900, "12:00", aiStatus = AiStatus.PENDING)

        val day = combine(meals(1, "10:00", 8) + pending).days.single()

        assertThat(day).isEqualTo(DayCombination.Held(LocalDate.parse("2026-10-19"), listOf(900L)))
    }

    @Test
    fun `X12 a day with a sensitive clue is blocked`() {
        val funeral = shot(900, "12:00", sensitive = SensitiveClueCode.FUNERAL_WREATH)

        val day = combine(meals(1, "10:00", 8) + funeral).days.single()

        assertThat(day).isInstanceOf(DayCombination.Blocked::class.java)
        val blocked = day as DayCombination.Blocked
        assertThat(blocked.findings.map { it.photoId }).containsExactly(900L)
        assertThat(blocked.findings.single().clue).isEqualTo(SensitiveClueCode.FUNERAL_WREATH)
    }

    @Test
    fun `a blocked day does not stop another day`() {
        val funeral = shot(900, "12:00", sensitive = SensitiveClueCode.FUNERAL_WREATH)
        val photos = meals(1, "10:00", 8) + funeral + meals(100, "10:00", 8, date = "2026-10-20")

        val days = combine(photos).days

        assertThat(days.map { it.dayKey }).containsExactly(LocalDate.parse("2026-10-19"), LocalDate.parse("2026-10-20"))
        assertThat(days[0]).isInstanceOf(DayCombination.Blocked::class.java)
        assertThat((days[1] as DayCombination.Bundled).bundles).hasSize(1)
    }

    @Test
    fun `X7 photos the analysis of which failed still make a bundle, because the content is not read`() {
        val failed = (1L..10L).map { shot(it, "10:%02d".format(it), member = 1 + it % 2, aiStatus = AiStatus.FAILED) }

        val bundle = bundles(failed).single()

        assertThat(bundle.photoCount).isEqualTo(10)
    }

    // --- the general bundles (13-2) ---

    @Test
    fun `a day of photos taken together is one general bundle with its card and its trace`() {
        val bundle = bundles(meals(1, "12:00", 8)).single()

        assertThat(bundle.kind).isEqualTo(BundleKind.GENERAL)
        assertThat(bundle.eventType).isNull()
        assertThat(bundle.establishedBy).isNull()
        assertThat(bundle.activity).isNull()
        assertThat(bundle.photoCount).isEqualTo(8)
        assertThat(onCard(bundle)).hasSize(8)
        assertThat(bundle.coverPhotoId).isEqualTo(onCard(bundle).first())
    }

    @Test
    fun `X3 a photo with a date only goes to the bundle of its batch, and is a candidate of its card`() {
        val batch = UUID.nameUUIDFromBytes("lunch".toByteArray())
        val lunch = meals(1, "12:00", 8, batch = { batch })

        val bundle = bundles(lunch + shot(99, null, batch = batch)).single()

        assertThat(bundle.photos.first { it.photoId == 99L }.role).isEqualTo(PhotoRole.ATTACHED_DATE_ONLY)
        assertThat(bundle.photoCount).isEqualTo(9)
        assertThat(onCard(bundle).last()).isEqualTo(99L)
    }

    @Test
    fun `X2 a photo that has no date is left out of the bundles, and no date is borrowed from the same picture`() {
        val lunch = meals(1, "12:00", 8).map { if (it.photoId == 1L) it.copy(fingerprint = scene(0)) else it }
        val undated = shot(50, null, member = 2, date = null, activity = MEAL, fingerprint = scene(0))

        val result = combine(lunch + undated)

        assertThat(result.excludedPhotoIds).containsExactly(50L)
        val bundle = (result.days.single() as DayCombination.Bundled).bundles.single()
        assertThat(photoIds(bundle)).doesNotContain(50L)
        assertThat(bundle.photoCount).isEqualTo(8)
    }

    @Test
    fun `X4 members in two time zones are read by the absolute time for the gaps, one lunch`() {
        val paris = (0 until 6).map {
            shot(1L + it, "14:%02d".format(5 * it), member = 1, activity = MEAL, offset = ZoneOffset.ofHours(1))
        }
        val seoul = (0 until 6).map {
            shot(11L + it, "22:%02d".format(5 * it), member = 2, activity = MEAL, offset = ZoneOffset.ofHours(9))
        }

        val result = bundles(paris + seoul)

        assertThat(result).hasSize(1)
        assertThat(result.single().photoCount).isEqualTo(12)
    }

    @Test
    fun `X5 the clock of a member that is 30 minutes slow is not corrected, the photos keep their own times`() {
        val own = meals(1, "12:00", 8, members = listOf(1))
        val slow = listOf(
            shot(101, "11:30", member = 2, activity = MEAL),
            shot(102, "11:40", member = 2, activity = MEAL)
        )

        val bundle = bundles(own + slow).single()

        // Nothing is moved: the bundle starts at the earliest time that was recorded, and all ten photos count.
        assertThat(bundle.startAt).isEqualTo(Instant.parse("2026-10-19T02:30:00Z"))
        assertThat(bundle.photoCount).isEqualTo(10)
        assertThat(bundle.uploaderCount).isEqualTo(2)
    }

    // --- the titles (9) ---

    @Test
    fun `every bundle has a title and none is blank`() {
        val result = bundles(meals(1, "10:00", 8) + meals(201, "15:00", 8))

        assertThat(result).hasSize(2)
        assertThat(result.map { it.title }).doesNotContainNull().allSatisfy { assertThat(it).isNotBlank() }
    }

    @Test
    fun `the year is in the title when the day is not of the year of the clock`() {
        val past = meals(1, "10:00", 8, date = "2025-10-19")

        assertThat(bundles(past).single().title).isEqualTo("2025년 10월 19일 오전")
    }

    @Test
    fun `the year of a title is the year in the zone of the settings, not the zone of the clock`() {
        // 16:00 UTC on the 31st of December is already the 1st of January in Seoul.
        val clock = Clock.fixed(Instant.parse("2026-12-31T16:00:00Z"), ZoneOffset.UTC)
        val lastYear = meals(1, "10:00", 8, date = "2026-12-30")
        val thisYear = meals(1, "10:00", 8, date = "2027-01-01")

        val titles = { photos: List<RecoPhoto> ->
            (BundleCombiner(clock = clock).combine(photos).days.single() as DayCombination.Bundled)
                .bundles.single().title
        }

        assertThat(titles(lastYear)).isEqualTo("2026년 12월 30일 오전")
        assertThat(titles(thisYear)).isEqualTo("1월 1일 오전")
    }

    @Test
    fun `a clock is not read before a day is named`() {
        val clock = org.mockito.Mockito.mock(Clock::class.java)

        BundleCombiner(clock = clock)

        org.mockito.Mockito.verifyNoInteractions(clock)
    }

    @Test
    fun `the clock is the only thing that changes a title`() {
        val photos = meals(1, "10:00", 8)
        val later = Clock.fixed(Instant.parse("2027-02-01T00:00:00Z"), ZoneOffset.UTC)

        val now = bundles(photos).map { it.title }
        val nextYear = (BundleCombiner(clock = later).combine(photos).days.single() as DayCombination.Bundled)
            .bundles.map { it.title }

        assertThat(now).containsExactly("10월 19일 오전")
        assertThat(nextYear).containsExactly("2026년 10월 19일 오전")
    }

    // --- the trace and the order (11, 1 principle 5) ---

    @Test
    fun `every bundle has a trace of its own kind, with the numbers and the card`() {
        val result = bundles(meals(1, "14:00", 8) + meals(201, "20:00", 8))

        assertThat(result).hasSize(2)
        result.forEach {
            assertThat(it.trace.dayKey).isEqualTo(it.dayKey)
            assertThat(it.trace.kind).isEqualTo(it.kind)
            assertThat(it.trace.configVersion).isEqualTo("reco-cfg-1")
            assertThat(it.trace.counts.photos).isEqualTo(it.photoCount)
            assertThat(it.trace.counts.uploaders).isEqualTo(it.uploaderCount)
            assertThat(it.trace.selection!!.cardSize).isEqualTo(onCard(it).size)
            assertThat(it.trace.general).isNotNull()
            assertThat(it.trace.event).isNull()
        }
    }

    @Test
    fun `the bundles of a day come in the order of the time they start`() {
        val result = bundles(meals(1, "18:00", 8) + meals(201, "09:00", 8))

        assertThat(result).hasSize(2)
        assertThat(result.map { it.startAt }).isSorted()
    }

    @Test
    fun `the same photos give the same bundles and the same traces, whatever the order they come in`() {
        val photos = meals(1, "12:00", 8) + meals(201, "15:00", 8)

        val straight = bundles(photos)
        val shuffled = bundles(photos.shuffled(Random(5)))
        val again = bundles(photos)

        assertThat(shuffled).isEqualTo(straight)
        assertThat(again).isEqualTo(straight)
    }

    @Test
    fun `each usable photo of a day is in one bundle at most`() {
        val photos = meals(1, "12:00", 8) + meals(201, "15:00", 8)

        val ids = bundles(photos).flatMap { photoIds(it) }

        assertThat(ids).doesNotHaveDuplicates()
    }

    @Test
    fun `the photos with no date are left out`() {
        val result = combine(meals(1, "12:00", 8) + shot(900, null, date = null))

        assertThat(result.excludedPhotoIds).containsExactly(900L)
    }
}
