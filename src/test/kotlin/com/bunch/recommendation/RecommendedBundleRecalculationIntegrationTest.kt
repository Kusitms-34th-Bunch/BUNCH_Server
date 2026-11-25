package com.bunch.recommendation

import com.bunch.bundle.domain.BundleEventType
import com.bunch.bundle.dto.BundleChangedEvent
import com.bunch.photo.domain.PhotoRecoFeatureRepository
import com.bunch.photo.service.PhotoChangedEvent
import com.bunch.recommendation.domain.RecommendedBundlePhotoRepository
import com.bunch.recommendation.domain.RecommendedBundleQueueRepository
import com.bunch.recommendation.domain.RecommendedBundleRepository
import com.bunch.recommendation.domain.combination.BundleExpiry
import com.bunch.recommendation.service.RecommendedBundleScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * The days that wait to be calculated and the calculation of the bunch when they are due (recommended bundle
 * specification 10-1, 10-2), with the switch of the new recommended bundles on, on a real PostgreSQL.
 */
@SpringBootTest(properties = ["reco.combination.enabled=true"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecommendedBundleRecalculationIntegrationTest : RecommendationIntegrationSupport() {
    @Autowired
    private lateinit var events: ApplicationEventPublisher

    @Autowired
    private lateinit var transactions: PlatformTransactionManager

    @Autowired
    private lateinit var queue: RecommendedBundleQueueRepository

    @Autowired
    private lateinit var scheduler: RecommendedBundleScheduler

    @Autowired
    private lateinit var bundles: RecommendedBundleRepository

    @Autowired
    private lateinit var bundlePhotos: RecommendedBundlePhotoRepository

    @Autowired
    private lateinit var features: PhotoRecoFeatureRepository

    private val day = LocalDate.parse("2026-10-19")
    private val answer = """{"clues":[],"activity":{"code":"MEAL","conf":0.9},"subject":"FOOD","people":"P0"}"""

    @AfterEach
    fun cleanNewFixtures() {
        jdbc.update("DELETE FROM recommended_bundle_recalc_queue")
        jdbc.update("DELETE FROM recommended_bundle_photos")
        jdbc.update("DELETE FROM recommended_bundles")
    }

    // --- the changes that put days in the queue (10-1) ---

    @Test
    fun `a change of a photo puts the day before and the day itself in the queue, after the quiet time`() {
        val (_, bunchId) = bunchWithOwner()
        val before = Instant.now()

        publish(PhotoChangedEvent(bunchId, day, photoAdded = true))

        val after = Instant.now()
        val rows = queuedDays(bunchId)
        assertThat(rows.keys).containsExactly(day.minusDays(1), day)
        rows.values.forEach {
            assertThat(it).isBetween(before.plusSeconds(300), after.plusSeconds(300))
        }
    }

    @Test
    fun `a change before the time moves the time later, so the day is calculated once, after the last change`() {
        val (_, bunchId) = bunchWithOwner()
        publish(PhotoChangedEvent(bunchId, day, photoAdded = true))
        jdbc.update(
            "UPDATE recommended_bundle_recalc_queue SET due_at = ? WHERE bunch_id = ?",
            Timestamp.from(Instant.now().plusSeconds(10)),
            bunchId
        )

        publish(PhotoChangedEvent(bunchId, day, photoAdded = false))

        assertThat(queuedDays(bunchId).values).allSatisfy { assertThat(it).isAfter(Instant.now().plusSeconds(280)) }
        assertThat(queuedDays(bunchId)).hasSize(2)
    }

    @Test
    fun `a photo with no date is part of no day and puts nothing in the queue`() {
        val (_, bunchId) = bunchWithOwner()

        publish(PhotoChangedEvent(bunchId, null, photoAdded = true))

        assertThat(queuedDays(bunchId)).isEmpty()
    }

    @Test
    fun `the photos of a bundle that a member makes put every day of the bunch in the queue`() {
        val (ownerId, bunchId) = bunchWithOwner()
        insertPhoto(bunchId, "2026-10-19", "10:00", uploaders = listOf(ownerId))
        insertPhoto(bunchId, "2026-10-19", "11:00", uploaders = listOf(ownerId))
        insertPhoto(bunchId, "2026-10-21", "10:00", uploaders = listOf(ownerId))
        insertPhoto(bunchId, null, uploaders = listOf(ownerId))

        publish(bundleEvent(bunchId, BundleEventType.PHOTOS))

        assertThat(queuedDays(bunchId).keys).containsExactly(day, LocalDate.parse("2026-10-21"))
    }

    @Test
    fun `a bundle that is created or deleted puts the days in the queue, the live editing does not`() {
        val (ownerId, bunchId) = bunchWithOwner()
        insertPhoto(bunchId, "2026-10-19", "10:00", uploaders = listOf(ownerId))

        listOf(BundleEventType.TITLE, BundleEventType.LOCATION, BundleEventType.COMMENT, BundleEventType.PRESENCE)
            .forEach { publish(bundleEvent(bunchId, it)) }
        assertThat(queuedDays(bunchId)).isEmpty()

        publish(bundleEvent(bunchId, BundleEventType.CREATED))
        assertThat(queuedDays(bunchId).keys).containsExactly(day)
        jdbc.update("DELETE FROM recommended_bundle_recalc_queue")
        publish(bundleEvent(bunchId, BundleEventType.DELETED))
        assertThat(queuedDays(bunchId).keys).containsExactly(day)
    }

    // --- the calculation of the bunch when its days are due (10-2) ---

    @Test
    fun `a day that is due is calculated, its bundle is saved and the day leaves the queue`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photoIds = lunch(bunchId, ownerId)
        queue.put(bunchId, day, Instant.now().minusSeconds(60))

        val looked = scheduler.runOnce(Instant.now())

        assertThat(looked).isEqualTo(1)
        val saved = bundles.findByBunchId(bunchId).single()
        assertThat(saved.title).isEqualTo("10월 19일 오전")
        assertThat(bundlePhotos.findByBundleIdIn(listOf(saved.id)).map { it.photoId })
            .containsExactlyInAnyOrderElementsOf(photoIds)
        assertThat(queuedDays(bunchId)).isEmpty()
    }

    @Test
    fun `a day that is not due yet is not calculated`() {
        val (ownerId, bunchId) = bunchWithOwner()
        lunch(bunchId, ownerId)
        queue.put(bunchId, day, Instant.now().plusSeconds(300))

        val looked = scheduler.runOnce(Instant.now())

        assertThat(looked).isZero()
        assertThat(bundles.findByBunchId(bunchId)).isEmpty()
        assertThat(queuedDays(bunchId)).hasSize(1)
    }

    @Test
    fun `a bunch with several days due is calculated once and all its due days leave the queue`() {
        val (ownerId, bunchId) = bunchWithOwner()
        lunch(bunchId, ownerId)
        val due = Instant.now().minusSeconds(60)
        queue.put(bunchId, day.minusDays(1), due)
        queue.put(bunchId, day, due)
        queue.put(bunchId, day.plusDays(5), Instant.now().plusSeconds(300))

        val looked = scheduler.runOnce(Instant.now())

        assertThat(looked).isEqualTo(1)
        assertThat(queuedDays(bunchId).keys).containsExactly(day.plusDays(5))
        assertThat(bundles.findByBunchId(bunchId)).hasSize(1)
    }

    @Test
    fun `a day that waits for the analysis is held, and is put in the queue for when the waiting is over`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photoIds = lunch(bunchId, ownerId, analyzed = false)
        photoIds.forEach { features.markPending(it) }
        queue.put(bunchId, day, Instant.now().minusSeconds(60))
        val before = Instant.now()

        scheduler.runOnce(Instant.now())

        assertThat(bundles.findByBunchId(bunchId)).isEmpty()
        val due = queuedDays(bunchId).getValue(day)
        // The photos were saved just now and may wait 30 minutes for the analysis (4-3).
        assertThat(due).isBetween(before.plusSeconds(29 * 60), Instant.now().plusSeconds(31 * 60))
    }

    @Test
    fun `the bundles of a held day stay as they are`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photoIds = lunch(bunchId, ownerId)
        queue.put(bunchId, day, Instant.now().minusSeconds(60))
        scheduler.runOnce(Instant.now())
        val saved = bundles.findByBunchId(bunchId).single()

        features.markPending(photoIds.first())
        queue.put(bunchId, day, Instant.now().minusSeconds(60))
        scheduler.runOnce(Instant.now())

        assertThat(bundles.findByBunchId(bunchId).single().id).isEqualTo(saved.id)
        assertThat(bundlePhotos.findByBundleIdIn(listOf(saved.id))).hasSize(8)
    }

    @Test
    fun `a bunch that is gone leaves nothing to calculate`() {
        val (ownerId, bunchId) = bunchWithOwner()
        lunch(bunchId, ownerId)
        queue.put(bunchId, day, Instant.now().minusSeconds(60))
        jdbc.update("DELETE FROM bunches WHERE id = ?", bunchId)

        assertThat(scheduler.runOnce(Instant.now())).isZero()
    }

    @Test
    fun `the same day calculated again keeps its bundle, so the list does not flicker`() {
        val (ownerId, bunchId) = bunchWithOwner()
        lunch(bunchId, ownerId)
        queue.put(bunchId, day, Instant.now().minusSeconds(60))
        scheduler.runOnce(Instant.now())
        val first = bundles.findByBunchId(bunchId).single()

        queue.put(bunchId, day, Instant.now().minusSeconds(60))
        scheduler.runOnce(Instant.now())

        val second = bundles.findByBunchId(bunchId).single()
        assertThat(second.id).isEqualTo(first.id)
        assertThat(second.createdAt).isEqualTo(first.createdAt)
        assertThat(second.visibleFrom).isEqualTo(first.visibleFrom)
    }

    // --- the 30 days (4.2.9 rule 4) ---

    @Test
    fun `a bundle whose photos were all added more than 30 days ago is not saved, and the day leaves the queue`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photoIds = lunch(bunchId, ownerId)
        addedDaysAgo(photoIds, 40)
        queue.put(bunchId, day, Instant.now().minusSeconds(60))

        scheduler.runOnce(Instant.now())

        assertThat(bundles.findByBunchId(bunchId)).isEmpty()
        assertThat(queuedDays(bunchId)).isEmpty()
    }

    @Test
    fun `the bundle comes back when a photo that was added since belongs to it`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photoIds = lunch(bunchId, ownerId)
        addedDaysAgo(photoIds, 40)
        val fresh = insertPhoto(bunchId, "2026-10-19", "11:20", uploaders = listOf(ownerId))
        features.markDone(fresh, "test-model", answer, Instant.now())
        queue.put(bunchId, day, Instant.now().minusSeconds(60))

        scheduler.runOnce(Instant.now())

        val saved = bundles.findByBunchId(bunchId).single()
        assertThat(saved.photoCount).isEqualTo(9)
        assertThat(saved.lastPhotoAddedAt).isAfter(Instant.now().minusSeconds(3_600))
    }

    @Test
    fun `a bundle that was saved is deleted by the next calculation once its photos are past 30 days`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photoIds = lunch(bunchId, ownerId)
        queue.put(bunchId, day, Instant.now().minusSeconds(60))
        scheduler.runOnce(Instant.now())
        assertThat(bundles.findByBunchId(bunchId)).hasSize(1)

        addedDaysAgo(photoIds, 40)
        queue.put(bunchId, day, Instant.now().minusSeconds(60))
        scheduler.runOnce(Instant.now())

        assertThat(bundles.findByBunchId(bunchId)).isEmpty()
        assertThat(bundlePhotos.findAll()).isEmpty()
    }

    @Test
    fun `the 30 days are counted in days, the last day is shown and the day after it is not`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photoIds = lunch(bunchId, ownerId)
        val now = Instant.now()
        val cutoff = BundleExpiry().cutoff(now)
        queue.put(bunchId, day, now.minusSeconds(60))
        jdbc.update("UPDATE photos SET created_at = ? WHERE id IN (${photoIds.joinToString()})", Timestamp.from(cutoff))

        scheduler.runOnce(now)
        assertThat(bundles.findByBunchId(bunchId)).hasSize(1)

        jdbc.update(
            "UPDATE photos SET created_at = ? WHERE id IN (${photoIds.joinToString()})",
            Timestamp.from(cutoff.minusSeconds(1))
        )
        queue.put(bunchId, day, now.minusSeconds(60))
        scheduler.runOnce(now)
        assertThat(bundles.findByBunchId(bunchId)).isEmpty()
    }

    @Test
    fun `the daily expiry deletes the bundles that are past 30 days with their photos, and keeps the others`() {
        val (ownerId, bunchId) = bunchWithOwner()
        lunch(bunchId, ownerId)
        queue.put(bunchId, day, Instant.now().minusSeconds(60))
        scheduler.runOnce(Instant.now())
        val saved = bundles.findByBunchId(bunchId).single()

        assertThat(scheduler.runExpiry(Instant.now())).isZero()
        assertThat(bundles.findByBunchId(bunchId)).hasSize(1)

        jdbc.update(
            "UPDATE recommended_bundles SET last_photo_added_at = ? WHERE id = ?",
            Timestamp.from(Instant.now().minusSeconds(40L * 86_400)),
            saved.id
        )
        assertThat(scheduler.runExpiry(Instant.now())).isEqualTo(1)

        assertThat(bundles.findByBunchId(bunchId)).isEmpty()
        assertThat(bundlePhotos.findByBundleIdIn(listOf(saved.id))).isEmpty()
    }

    @Test
    fun `a bundle that has expired is not made again by a calculation that has nothing new`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photoIds = lunch(bunchId, ownerId)
        addedDaysAgo(photoIds, 40)

        repeat(2) {
            queue.put(bunchId, day, Instant.now().minusSeconds(60))
            scheduler.runOnce(Instant.now())
        }

        assertThat(bundles.findByBunchId(bunchId)).isEmpty()
    }

    // --- helpers ---

    private fun addedDaysAgo(photoIds: List<Long>, days: Long) {
        jdbc.update(
            "UPDATE photos SET created_at = ? WHERE id IN (${photoIds.joinToString()})",
            Timestamp.from(Instant.now().minusSeconds(days * 86_400))
        )
    }


    private fun publish(event: Any) {
        TransactionTemplate(transactions).executeWithoutResult { events.publishEvent(event) }
    }

    private fun bundleEvent(bunchId: Long, type: BundleEventType) =
        BundleChangedEvent(bunchId, 987_654L, 1L, "request", type, null)

    private fun queuedDays(bunchId: Long): Map<LocalDate, Instant> = jdbc.query(
        "SELECT day_key, due_at FROM recommended_bundle_recalc_queue WHERE bunch_id = ? ORDER BY day_key",
        { rs, _ -> rs.getDate("day_key").toLocalDate() to rs.getTimestamp("due_at").toInstant() },
        bunchId
    ).toMap(LinkedHashMap())

    // A lunch of 8 photos, one every ten minutes, uploaded by two members in turn, of which the analysis says a meal.
    private fun lunch(bunchId: Long, ownerId: Long, analyzed: Boolean = true): List<Long> {
        val other = joined(ownerId, bunchId)
        return (0 until 8).map { i ->
            val time = LocalTime.of(10, 0).plusMinutes(10L * i).toString()
            val uploader = if (i % 2 == 0) ownerId else other
            val photoId = insertPhoto(bunchId, "2026-10-19", time, uploaders = listOf(uploader))
            if (analyzed) features.markDone(photoId, "test-model", answer, Instant.now())
            photoId
        }
    }
}
