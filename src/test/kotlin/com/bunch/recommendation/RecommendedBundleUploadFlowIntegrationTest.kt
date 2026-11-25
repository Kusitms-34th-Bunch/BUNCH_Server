package com.bunch.recommendation

import com.bunch.photo.PhotoUploadTestSupport
import com.bunch.photo.domain.PhotoRecoFeatureRepository
import com.bunch.recommendation.domain.RecommendedBundlePhotoRepository
import com.bunch.recommendation.domain.RecommendedBundleRepository
import com.bunch.recommendation.service.RecommendedBundleScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * The whole way of the new recommended bundles, from the upload of a photo to the saved bundle (recommended bundle
 * specification 10-1 to 10-3), with the switch on and the photos uploaded through the photo service as a member does:
 * the upload gives the event, the event puts the days in the queue, the scheduler calculates the bunch when the days
 * are due, and the bundle is saved. Only two things are not the real ones: the storage of the image (made by hand, as
 * in the other upload tests) and the AI, whose answer is put in the photos by hand, because nothing asks for it yet.
 */
@SpringBootTest(properties = ["reco.combination.enabled=true"])
@ActiveProfiles("test")
class RecommendedBundleUploadFlowIntegrationTest : PhotoUploadTestSupport() {
    @Autowired
    private lateinit var scheduler: RecommendedBundleScheduler

    @Autowired
    private lateinit var bundles: RecommendedBundleRepository

    @Autowired
    private lateinit var bundlePhotos: RecommendedBundlePhotoRepository

    @Autowired
    private lateinit var features: PhotoRecoFeatureRepository

    private val day = LocalDate.of(2026, 10, 19)
    private val meal = """{"clues":[],"activity":{"code":"MEAL","conf":0.9},"subject":"FOOD","people":"P0"}"""

    @AfterEach
    fun cleanNewFixtures() {
        jdbc.update("DELETE FROM recommended_bundle_recalc_queue")
        jdbc.update("DELETE FROM recommended_bundle_photos")
        jdbc.update("DELETE FROM recommended_bundles")
    }

    @Test
    fun `a photo that is uploaded puts the day before and its day in the queue, after the quiet time`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val before = Instant.now()

        upload(bunchId, ownerId, "first", LocalTime.of(10, 0))

        val after = Instant.now()
        val queued = jdbc.query(
            "SELECT day_key, due_at FROM recommended_bundle_recalc_queue WHERE bunch_id = ? ORDER BY day_key",
            { rs, _ -> rs.getDate("day_key").toLocalDate() to rs.getTimestamp("due_at").toInstant() },
            bunchId
        )
        assertThat(queued.map { it.first }).containsExactly(day.minusDays(1), day)
        queued.forEach { assertThat(it.second).isBetween(before.plusSeconds(300), after.plusSeconds(300)) }
    }

    @Test
    fun `photos that are uploaded become a recommended bundle when the queue is due`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherId = joined(ownerId, bunchId)
        val photoIds = (0 until 8).map { i ->
            upload(bunchId, if (i % 2 == 0) ownerId else otherId, "lunch-$i", LocalTime.of(10, 0).plusMinutes(10L * i))
        }
        analyzed(photoIds)
        makeDue(bunchId)

        val looked = scheduler.runOnce(Instant.now())

        assertThat(looked).isEqualTo(1)
        val saved = bundles.findByBunchId(bunchId).single()
        assertThat(saved.dayKey).isEqualTo(day)
        assertThat(saved.title).isEqualTo("10월 19일 오전")
        assertThat(saved.photoCount).isEqualTo(8)
        assertThat(saved.uploaderCount).isEqualTo(2)
        assertThat(bundlePhotos.findByBundleIdIn(listOf(saved.id)).map { it.photoId })
            .containsExactlyInAnyOrderElementsOf(photoIds)
        assertThat(queued()).isZero()
    }

    @Test
    fun `a bundle is not made before the quiet time is over`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherId = joined(ownerId, bunchId)
        val photoIds = (0 until 8).map { i ->
            upload(bunchId, if (i % 2 == 0) ownerId else otherId, "early-$i", LocalTime.of(10, 0).plusMinutes(10L * i))
        }
        analyzed(photoIds)

        val looked = scheduler.runOnce(Instant.now())

        assertThat(looked).isZero()
        assertThat(bundles.findByBunchId(bunchId)).isEmpty()
    }

    @Test
    fun `a photo uploaded after the bundle was made makes a new bundle of the day that replaces the old one`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherId = joined(ownerId, bunchId)
        val photoIds = (0 until 8).map { i ->
            upload(bunchId, if (i % 2 == 0) ownerId else otherId, "grow-$i", LocalTime.of(10, 0).plusMinutes(10L * i))
        }
        analyzed(photoIds)
        makeDue(bunchId)
        scheduler.runOnce(Instant.now())
        val first = bundles.findByBunchId(bunchId).single()

        val ninth = upload(bunchId, ownerId, "grow-9", LocalTime.of(11, 20))
        analyzed(listOf(ninth))
        makeDue(bunchId)
        scheduler.runOnce(Instant.now())

        val second = bundles.findByBunchId(bunchId).single()
        assertThat(second.id).isNotEqualTo(first.id)
        assertThat(second.photoCount).isEqualTo(9)
        assertThat(bundlePhotos.findByBundleIdIn(listOf(first.id))).isEmpty()
        assertThat(bundlePhotos.findByBundleIdIn(listOf(second.id))).hasSize(9)
    }

    @Test
    fun `photos of fewer members than a bundle needs are not recommended, and the day leaves the queue`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photoIds = (0 until 8).map { i ->
            upload(bunchId, ownerId, "alone-$i", LocalTime.of(10, 0).plusMinutes(10L * i))
        }
        analyzed(photoIds)
        makeDue(bunchId)

        scheduler.runOnce(Instant.now())

        assertThat(bundles.findByBunchId(bunchId)).isEmpty()
        assertThat(queued()).isZero()
    }

    // --- helpers ---

    private fun upload(bunchId: Long, memberId: Long, seed: String, time: LocalTime): Long =
        uploads.upload(command(bunchId, memberId, capturedDate = day, capturedTime = time), image(seed)).photoId

    private fun queued(): Int =
        jdbc.queryForObject("SELECT count(*) FROM recommended_bundle_recalc_queue", Int::class.java)!!

    // What the analysis would have said of the photos: a meal.
    private fun analyzed(photoIds: List<Long>) {
        photoIds.forEach { features.markDone(it, "test-model", meal, Instant.now()) }
    }

    // The quiet time is over for the days of the bunch.
    private fun makeDue(bunchId: Long) {
        jdbc.update(
            "UPDATE recommended_bundle_recalc_queue SET due_at = ? WHERE bunch_id = ?",
            Timestamp.from(Instant.now().minusSeconds(60)),
            bunchId
        )
    }
}
