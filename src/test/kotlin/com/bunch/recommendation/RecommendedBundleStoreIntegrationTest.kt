package com.bunch.recommendation

import com.bunch.photo.PhotoHttpTestSupport
import com.bunch.recommendation.combination.CombinerTestSupport.CLOCK
import com.bunch.recommendation.combination.CombinerTestSupport.shot
import com.bunch.recommendation.domain.RecommendedBundlePhotoRepository
import com.bunch.recommendation.domain.RecommendedBundleRepository
import com.bunch.recommendation.domain.combination.ActivityCode
import com.bunch.recommendation.domain.combination.BundleCombiner
import com.bunch.recommendation.domain.combination.BundleKind
import com.bunch.recommendation.domain.combination.BundleStableKey
import com.bunch.recommendation.domain.combination.CombinationResult
import com.bunch.recommendation.domain.combination.DayCombination
import com.bunch.recommendation.domain.combination.PhotoRole
import com.bunch.recommendation.domain.combination.RecoBundle
import com.bunch.recommendation.domain.combination.RecoPhoto
import com.bunch.recommendation.domain.combination.RuleTrace
import com.bunch.recommendation.service.RecommendedBundleStore
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * The saving of the calculation of a bunch as its recommended bundles (recommended bundle specification 10-3, 10-4), on
 * a real PostgreSQL: what is kept, changed, deleted and added when a day is calculated again.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecommendedBundleStoreIntegrationTest : PhotoHttpTestSupport() {
    @Autowired
    private lateinit var store: RecommendedBundleStore

    @Autowired
    private lateinit var bundles: RecommendedBundleRepository

    @Autowired
    private lateinit var bundlePhotos: RecommendedBundlePhotoRepository

    private val day = LocalDate.parse("2026-10-19")
    private val nextDay = LocalDate.parse("2026-10-20")

    // 23:00 on the 19th in Seoul, before the day is over. The day is over at 05:00 on the 20th, which is 20:00 in UTC.
    private val evening = Instant.parse("2026-10-19T14:00:00Z")
    private val dayEnd = Instant.parse("2026-10-19T20:00:00Z")

    @AfterEach
    fun cleanFixtures() {
        jdbc.update("DELETE FROM recommended_bundle_recalc_queue")
        jdbc.update("DELETE FROM recommended_bundle_photos")
        jdbc.update("DELETE FROM recommended_bundles")
        jdbc.update("DELETE FROM photo_uploaders")
        jdbc.update("DELETE FROM photos")
        jdbc.update("DELETE FROM image_assets WHERE object_key LIKE 'photos/test-http-%/original.jpg'")
    }

    // --- what is saved ---

    @Test
    fun `a day is saved with its bundle, its photos, its card and the way it was made`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photos = lunch(bunchId, ownerId)

        val outcome = store.replace(bunchId, combine(photos), evening)

        val saved = bundles.findByBunchId(bunchId).single()
        assertThat(outcome.created).isEqualTo(1)
        assertThat(saved.title).isEqualTo("10월 19일 오전")
        assertThat(saved.kind).isEqualTo(BundleKind.GENERAL)
        assertThat(saved.activity).isNull()
        assertThat(saved.dayKey).isEqualTo(day)
        assertThat(saved.photoCount).isEqualTo(8)
        assertThat(saved.uploaderCount).isEqualTo(2)
        assertThat(saved.createdAt).isEqualTo(evening)
        assertThat(saved.stableKey).isEqualTo(BundleStableKey.of(bunchId, day, photos.map { it.photoId }))
        val rows = bundlePhotos.findByBundleIdIn(listOf(saved.id))
        assertThat(rows.map { it.photoId }).containsExactlyInAnyOrderElementsOf(photos.map { it.photoId })
        assertThat(rows.mapNotNull { it.cardPosition }.sorted()).isEqualTo((1..8).toList())
        @Suppress("UNCHECKED_CAST")
        assertThat((saved.ruleTrace["counts"] as Map<String, Any?>)["photos"]).isEqualTo(8)
    }

    @Test
    fun `a day that is not over is shown when it is over, a day that is over is shown at once`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photos = lunch(bunchId, ownerId)

        store.replace(bunchId, combine(photos), evening)
        assertThat(bundles.findByBunchId(bunchId).single().visibleFrom).isEqualTo(dayEnd)

        val (otherOwner, otherBunch) = bunchWithOwner()
        val late = Instant.parse("2026-10-25T03:00:00Z")
        store.replace(otherBunch, combine(lunch(otherBunch, otherOwner)), late)
        assertThat(bundles.findByBunchId(otherBunch).single().visibleFrom).isEqualTo(late)
    }

    // --- a day that is calculated again (10-3) ---

    @Test
    fun `the same calculation again changes nothing, not even the rows of the photos`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photos = lunch(bunchId, ownerId)
        store.replace(bunchId, combine(photos), evening)
        val before = bundles.findByBunchId(bunchId).single()
        val rowIds = bundlePhotos.findByBundleIdIn(listOf(before.id)).map { it.id }.sorted()

        val outcome = store.replace(bunchId, combine(photos), evening.plusSeconds(600))

        val after = bundles.findByBunchId(bunchId).single()
        assertThat(outcome.unchanged).isEqualTo(1)
        assertThat(outcome.updated).isZero()
        assertThat(outcome.created).isZero()
        assertThat(outcome.removed).isZero()
        assertThat(after.id).isEqualTo(before.id)
        assertThat(after.updatedAt).isEqualTo(before.updatedAt)
        assertThat(bundlePhotos.findByBundleIdIn(listOf(after.id)).map { it.id }.sorted()).isEqualTo(rowIds)
    }

    @Test
    fun `a bundle with the same photos keeps its number and times, and takes the new title`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photos = lunch(bunchId, ownerId)
        store.replace(bunchId, combine(photos), evening)
        val before = bundles.findByBunchId(bunchId).single()
        val nextYear = Clock.fixed(Instant.parse("2027-02-01T00:00:00Z"), ZoneOffset.UTC)
        val later = Instant.parse("2027-02-01T00:00:00Z")

        val outcome = store.replace(bunchId, BundleCombiner(clock = nextYear).combine(photos), later)

        val after = bundles.findByBunchId(bunchId).single()
        assertThat(outcome.updated).isEqualTo(1)
        assertThat(outcome.created).isZero()
        assertThat(after.id).isEqualTo(before.id)
        assertThat(after.createdAt).isEqualTo(before.createdAt)
        assertThat(after.visibleFrom).isEqualTo(before.visibleFrom)
        assertThat(after.title).isEqualTo("2026년 10월 19일 오전")
        assertThat(after.updatedAt).isEqualTo(later)
    }

    @Test
    fun `a photo more makes another bundle, the old one is deleted and the new one is added`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photos = lunch(bunchId, ownerId)
        store.replace(bunchId, combine(photos), evening)
        val before = bundles.findByBunchId(bunchId).single()
        val more = photos + shot(photoId(bunchId, ownerId, "11:20"), "11:20", 1, activity = ActivityCode.MEAL)

        val outcome = store.replace(bunchId, combine(more), evening.plusSeconds(600))

        val after = bundles.findByBunchId(bunchId).single()
        assertThat(outcome.created).isEqualTo(1)
        assertThat(outcome.removed).isEqualTo(1)
        assertThat(after.id).isNotEqualTo(before.id)
        assertThat(after.photoCount).isEqualTo(9)
        assertThat(bundlePhotos.findByBundleIdIn(listOf(before.id))).isEmpty()
        assertThat(bundlePhotos.findByBundleIdIn(listOf(after.id))).hasSize(9)
    }

    @Test
    fun `photos that go from two bundles to two others are saved without being in two at once`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val ids = (1..12).map { photoId(bunchId, ownerId, "10:%02d".format(it)) }
        val first = manual(day, ids.subList(0, 6))
        val second = manual(day, ids.subList(6, 12))
        store.replace(bunchId, bundled(day, first, second), evening)

        // The 6th photo goes from the first bundle to the second.
        val first2 = manual(day, ids.subList(0, 5))
        val second2 = manual(day, ids.subList(5, 12))
        val outcome = store.replace(bunchId, bundled(day, first2, second2), evening.plusSeconds(60))

        assertThat(outcome.created).isEqualTo(2)
        assertThat(outcome.removed).isEqualTo(2)
        val photosOfBundles = bundles.findByBunchId(bunchId).associate { it.photoCount to it.id }
        assertThat(photosOfBundles.keys).containsExactlyInAnyOrder(5, 7)
        assertThat(bundlePhotos.findAll()).hasSize(12)
    }

    @Test
    fun `the places on the card are written again when the card changes, without clashing`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val ids = (1..6).map { photoId(bunchId, ownerId, "10:%02d".format(it)) }
        val bundle = manual(day, ids, positions = ids.zip(1..6).toMap())
        store.replace(bunchId, bundled(day, bundle), evening)
        val before = bundles.findByBunchId(bunchId).single()

        val swapped = manual(day, ids, positions = ids.zip(6 downTo 1).toMap())
        val outcome = store.replace(bunchId, bundled(day, swapped), evening.plusSeconds(60))

        assertThat(outcome.updated).isEqualTo(1)
        assertThat(outcome.created).isZero()
        val after = bundles.findByBunchId(bunchId).single()
        assertThat(after.id).isEqualTo(before.id)
        val positions = bundlePhotos.findByBundleIdIn(listOf(after.id)).associate { it.photoId to it.cardPosition }
        assertThat(positions).isEqualTo(ids.zip(6 downTo 1).toMap())
    }

    // --- what a day says (5-6) ---

    @Test
    fun `a day with nothing to recommend deletes the bundles it had`() {
        val (ownerId, bunchId) = bunchWithOwner()
        store.replace(bunchId, combine(lunch(bunchId, ownerId)), evening)

        val outcome = store.replace(bunchId, bundled(day), evening.plusSeconds(60))

        assertThat(outcome.removed).isEqualTo(1)
        assertThat(bundles.findByBunchId(bunchId)).isEmpty()
        assertThat(bundlePhotos.findAll()).isEmpty()
    }

    @Test
    fun `a blocked day deletes the bundles it had`() {
        val (ownerId, bunchId) = bunchWithOwner()
        store.replace(bunchId, combine(lunch(bunchId, ownerId)), evening)

        val blocked = CombinationResult(listOf(DayCombination.Blocked(day, emptyList())), emptyList())
        val outcome = store.replace(bunchId, blocked, evening.plusSeconds(60))

        assertThat(outcome.removed).isEqualTo(1)
        assertThat(bundles.findByBunchId(bunchId)).isEmpty()
    }

    @Test
    fun `a held day keeps the bundles it had, as they are`() {
        val (ownerId, bunchId) = bunchWithOwner()
        store.replace(bunchId, combine(lunch(bunchId, ownerId)), evening)
        val before = bundles.findByBunchId(bunchId).single()

        val held = CombinationResult(listOf(DayCombination.Held(day, listOf(99))), emptyList())
        val outcome = store.replace(bunchId, held, evening.plusSeconds(60))

        assertThat(outcome.removed).isZero()
        assertThat(outcome.created).isZero()
        assertThat(bundles.findByBunchId(bunchId).single().id).isEqualTo(before.id)
        assertThat(bundlePhotos.findByBundleIdIn(listOf(before.id))).hasSize(8)
    }

    @Test
    fun `a day that is not in the calculation any more loses its bundles`() {
        val (ownerId, bunchId) = bunchWithOwner()
        store.replace(bunchId, combine(lunch(bunchId, ownerId)), evening)

        val outcome = store.replace(bunchId, CombinationResult(emptyList(), emptyList()), evening)

        assertThat(outcome.removed).isEqualTo(1)
        assertThat(bundles.findByBunchId(bunchId)).isEmpty()
    }

    @Test
    fun `several days are saved at once, each its own, and a held one is left alone`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val first = manual(day, (1..6).map { photoId(bunchId, ownerId, "10:%02d".format(it)) })
        val second = manual(nextDay, (1..6).map { photoId(bunchId, ownerId, "11:%02d".format(it), "2026-10-20") })
        store.replace(bunchId, bundled(day, first), evening)

        val result = CombinationResult(
            listOf(DayCombination.Held(day, listOf(99)), DayCombination.Bundled(nextDay, listOf(second))),
            emptyList()
        )
        val outcome = store.replace(bunchId, result, evening.plusSeconds(60))

        assertThat(outcome.created).isEqualTo(1)
        assertThat(outcome.removed).isZero()
        assertThat(bundles.findByBunchId(bunchId).map { it.dayKey }).containsExactlyInAnyOrder(day, nextDay)
    }

    @Test
    fun `a photo that goes to another day is taken out of a held day, and that day is told to be calculated`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val ids = (1..6).map { photoId(bunchId, ownerId, "10:%02d".format(it)) }
        store.replace(bunchId, bundled(day, manual(day, ids)), evening)
        val moving = ids.first()

        val result = CombinationResult(
            listOf(
                DayCombination.Held(day, listOf(99)),
                DayCombination.Bundled(nextDay, listOf(manual(nextDay, listOf(moving) + extra(bunchId, ownerId, 5))))
            ),
            emptyList()
        )
        val outcome = store.replace(bunchId, result, evening.plusSeconds(60))

        assertThat(outcome.staleDays).containsExactly(day)
        val ofNextDay = bundles.findByBunchIdAndDayKeyIn(bunchId, listOf(nextDay)).single()
        assertThat(bundlePhotos.findByBundleIdIn(listOf(ofNextDay.id)).map { it.photoId }).contains(moving)
        val ofDay = bundles.findByBunchIdAndDayKeyIn(bunchId, listOf(day)).single()
        assertThat(bundlePhotos.findByBundleIdIn(listOf(ofDay.id))).hasSize(5)
    }

    // --- when the last photo was added (4.2.9 rule 4) ---

    @Test
    fun `a new bundle remembers when the last of its photos was added`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photos = lunch(bunchId, ownerId)
        val addedAt = photos.mapIndexed { index, photo ->
            photo.photoId to Instant.parse("2026-10-10T00:00:00Z").plusSeconds(3_600L * index)
        }.toMap()

        store.replace(bunchId, combine(photos), evening, addedAt)

        assertThat(bundles.findByBunchId(bunchId).single().lastPhotoAddedAt)
            .isEqualTo(Instant.parse("2026-10-10T07:00:00Z"))
    }

    @Test
    fun `a photo that has no time of being added counts as added when the calculation was made`() {
        val (ownerId, bunchId) = bunchWithOwner()

        store.replace(bunchId, combine(lunch(bunchId, ownerId)), evening)

        assertThat(bundles.findByBunchId(bunchId).single().lastPhotoAddedAt).isEqualTo(evening)
    }

    @Test
    fun `a bundle that is kept keeps its time, it has the same photos`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photos = lunch(bunchId, ownerId)
        val first = photos.associate { it.photoId to Instant.parse("2026-10-10T00:00:00Z") }
        val later = photos.associate { it.photoId to Instant.parse("2026-10-18T00:00:00Z") }
        store.replace(bunchId, combine(photos), evening, first)

        val outcome = store.replace(bunchId, combine(photos), evening.plusSeconds(60), later)

        assertThat(outcome.unchanged).isEqualTo(1)
        assertThat(bundles.findByBunchId(bunchId).single().lastPhotoAddedAt)
            .isEqualTo(Instant.parse("2026-10-10T00:00:00Z"))
    }

    // --- what is refused ---

    @Test
    fun `a photo in two bundles of the calculation is refused and nothing is changed`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val ids = (1..8).map { photoId(bunchId, ownerId, "10:%02d".format(it)) }
        store.replace(bunchId, bundled(day, manual(day, ids.subList(0, 6))), evening)
        val before = bundles.findByBunchId(bunchId).single()

        val twice = bundled(day, manual(day, ids.subList(0, 5)), manual(day, ids.subList(4, 8)))

        assertThatThrownBy { store.replace(bunchId, twice, evening.plusSeconds(60)) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(bundles.findByBunchId(bunchId).single().id).isEqualTo(before.id)
        assertThat(bundlePhotos.findByBundleIdIn(listOf(before.id))).hasSize(6)
    }

    @Test
    fun `a bundle that is not named is refused`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val ids = (1..6).map { photoId(bunchId, ownerId, "10:%02d".format(it)) }
        val unnamed = manual(day, ids).copy(title = null)

        assertThatThrownBy { store.replace(bunchId, bundled(day, unnamed), evening) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(bundles.findByBunchId(bunchId)).isEmpty()
    }

    @Test
    fun `the bundles of another bunch are not touched`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val (otherOwner, otherBunch) = bunchWithOwner()
        store.replace(otherBunch, combine(lunch(otherBunch, otherOwner)), evening)
        val other = bundles.findByBunchId(otherBunch).single()

        store.replace(bunchId, combine(lunch(bunchId, ownerId)), evening)
        store.replace(bunchId, CombinationResult(emptyList(), emptyList()), evening)

        assertThat(bundles.findByBunchId(otherBunch).single().id).isEqualTo(other.id)
        assertThat(bundlePhotos.findByBundleIdIn(listOf(other.id))).hasSize(8)
    }

    // --- helpers ---

    private fun combine(photos: List<RecoPhoto>): CombinationResult = BundleCombiner(clock = CLOCK).combine(photos)

    private fun bundled(dayKey: LocalDate, vararg bundles: RecoBundle) =
        CombinationResult(listOf(DayCombination.Bundled(dayKey, bundles.toList())), emptyList())

    private fun photoId(bunchId: Long, ownerId: Long, time: String, date: String = "2026-10-19"): Long =
        insertPhoto(bunchId, date, time, uploaders = listOf(ownerId))

    // Photos of the bunch that are only there to be put in a bundle by hand.
    private fun extra(bunchId: Long, ownerId: Long, count: Int): List<Long> =
        (1..count).map { photoId(bunchId, ownerId, "12:%02d".format(it), "2026-10-20") }

    // A lunch of [count] photos, one every ten minutes, saved as photos of the bunch so that they can be in a bundle.
    private fun lunch(bunchId: Long, ownerId: Long, from: String = "10:00", count: Int = 8): List<RecoPhoto> =
        (0 until count).map { i ->
            val time = LocalTime.parse(from).plusMinutes(10L * i).toString()
            shot(photoId(bunchId, ownerId, time), time, (i % 2 + 1).toLong(), activity = ActivityCode.MEAL)
        }

    // A general bundle with the photos given, as the calculation would have made it, with a title and a card.
    private fun manual(dayKey: LocalDate, ids: List<Long>, positions: Map<Long, Int>? = null): RecoBundle {
        val card = positions ?: ids.withIndex().associate { (index, id) -> id to index + 1 }
        return RecoBundle(
            dayKey = dayKey,
            kind = BundleKind.GENERAL,
            eventType = null,
            establishedBy = null,
            activity = ActivityCode.MEAL,
            subIndex = null,
            photos = ids.map { RecoBundle.BundlePhoto(it, PhotoRole.GENERAL, card[it]) },
            coverPhotoId = null,
            startAt = Instant.parse("2026-10-19T10:00:00Z"),
            endAt = Instant.parse("2026-10-19T11:00:00Z"),
            photoCount = ids.size,
            uploaderCount = 2,
            hasGroupPhoto = false,
            trace = RuleTrace(dayKey, BundleKind.GENERAL, RuleTrace.Counts(ids.size, 2), "reco-cfg-1"),
            title = "${dayKey.monthValue}월 ${dayKey.dayOfMonth}일 식사"
        )
    }
}
