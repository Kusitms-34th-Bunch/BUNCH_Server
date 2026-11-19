package com.bunch.recommendation

import com.bunch.bundle.domain.BundleEventType
import com.bunch.bundle.dto.BundleActor
import com.bunch.bundle.dto.BundleChangedEvent
import com.bunch.bundle.dto.CreateBundleRequest
import com.bunch.bundle.service.BundleService
import com.bunch.global.exception.ErrorCode
import com.bunch.photo.service.PhotoChangedEvent
import com.bunch.recommendation.analysis.PhotoAnalysisFakesConfig
import com.bunch.recommendation.analysis.ScriptedPhotoAnalyzer
import com.bunch.recommendation.domain.RecommendedBundleQueueRepository
import com.bunch.recommendation.domain.RecommendedBundleRepository
import com.bunch.recommendation.service.RecommendedBundleScheduler
import com.bunch.recommendation.service.analysis.PhotoAnalysisService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders.AUTHORIZATION
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The new recommended bundles are off by default (`reco.combination.enabled`): the recommended bundles that are shown
 * are still the old ones, so nothing is put in the queue and nothing is calculated or kept, whatever happens to the
 * photos. This runs with the settings of a test as they are, which is how the application starts without the switch.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PhotoAnalysisFakesConfig::class)
class RecommendedBundleSwitchedOffIntegrationTest : RecommendationIntegrationSupport() {
    @Autowired
    private lateinit var events: ApplicationEventPublisher

    @Autowired
    private lateinit var drafts: BundleService

    @Autowired
    private lateinit var transactions: PlatformTransactionManager

    @Autowired
    private lateinit var queue: RecommendedBundleQueueRepository

    @Autowired
    private lateinit var scheduler: RecommendedBundleScheduler

    @Autowired
    private lateinit var bundles: RecommendedBundleRepository

    @Autowired
    private lateinit var analysis: PhotoAnalysisService

    @Autowired
    private lateinit var analyzer: ScriptedPhotoAnalyzer

    private val day = LocalDate.parse("2026-10-19")

    @BeforeEach
    fun resetAnalyzer() {
        analyzer.reset()
    }

    @AfterEach
    fun cleanNewFixtures() {
        jdbc.update("DELETE FROM recommended_bundle_recalc_queue")
        jdbc.update("DELETE FROM recommended_bundle_photos")
        jdbc.update("DELETE FROM recommended_bundles")
    }

    @Test
    fun `the changes of the photos and of the bundles put nothing in the queue`() {
        val (ownerId, bunchId) = bunchWithOwner()
        insertPhoto(bunchId, "2026-10-19", "10:00", uploaders = listOf(ownerId))

        publish(PhotoChangedEvent(bunchId, day, photoAdded = true))
        publish(BundleChangedEvent(bunchId, 987_654L, 1L, "request", BundleEventType.PHOTOS, null))

        assertThat(count("recommended_bundle_recalc_queue", "bunch_id = $bunchId")).isZero()
    }

    @Test
    fun `the end of the analysis of a photo puts nothing in the queue`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photoId = insertPhoto(bunchId, "2026-10-19", "10:00", uploaders = listOf(ownerId))

        analysis.analyze(photoId)

        assertThat(count("recommended_bundle_recalc_queue", "bunch_id = $bunchId")).isZero()
    }

    @Test
    fun `the scheduler calculates nothing even when a day is due`() {
        val (ownerId, bunchId) = bunchWithOwner()
        insertPhoto(bunchId, "2026-10-19", "10:00", uploaders = listOf(ownerId))
        queue.put(bunchId, day, Instant.now().minusSeconds(60))

        val looked = scheduler.runOnce(Instant.now())

        assertThat(looked).isZero()
        assertThat(bundles.findByBunchId(bunchId)).isEmpty()
        assertThat(count("recommended_bundle_recalc_queue", "bunch_id = $bunchId")).isEqualTo(1)
    }

    @Test
    fun `the daily expiry deletes nothing, even a bundle that is past 30 days`() {
        val (_, bunchId) = bunchWithOwner()
        jdbc.update(
            """INSERT INTO recommended_bundles (bunch_id, day_key, stable_key, kind, title, start_at, end_at,
                photo_count, uploader_count, has_group_photo, rule_trace, visible_from, last_photo_added_at,
                created_at, updated_at)
                VALUES (?, DATE '2026-09-01', 'old-key', 'GENERAL', '9월 1일', now(), now(), 6, 2, false, '{}',
                now(), now() - interval '40 days', now(), now())""",
            bunchId
        )

        val removed = scheduler.runExpiry(Instant.now())

        assertThat(removed).isZero()
        assertThat(count("recommended_bundles", "bunch_id = $bunchId")).isEqualTo(1)
    }

    @Test
    fun `the sheet is the old one, a bundle of the new combination is not on it and is not found`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photoId = insertPhoto(bunchId, "2026-10-19", "10:00", uploaders = listOf(ownerId))
        val id = insertNewBundle(bunchId, photoId)

        val sheet = "/api/v1/bunches/$bunchId/recommended-bundles"
        val list = mvc.perform(get(sheet).header(AUTHORIZATION, bearer(ownerId)))
            .andExpect(status().isOk).andReturn().data()
        mvc.perform(get("$sheet/$id").header(AUTHORIZATION, bearer(ownerId)))
            .expectError(ErrorCode.RESOURCE_NOT_FOUND)

        assertThat(list.path("bundles")).isEmpty()
    }

    @Test
    fun `a draft does not take a bundle of the new combination`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val partner = joined(ownerId, bunchId)
        val photoId = insertPhoto(bunchId, "2026-10-19", "10:00", uploaders = listOf(ownerId))
        val id = insertNewBundle(bunchId, photoId)

        val draft = drafts.create(
            BundleActor(ownerId, "session"),
            bunchId,
            UUID.randomUUID(),
            CreateBundleRequest(listOf(photoId), id, participantMemberIds = listOf(partner))
        )

        assertThat(draft.title).isEmpty()
        assertThat(count("recommended_bundles", "id = $id")).isEqualTo(1)
        assertThat(count("recommendation_used_photos", "bunch_id = $bunchId")).isZero()
    }

    private fun insertNewBundle(bunchId: Long, photoId: Long): Long {
        val id = jdbc.queryForObject(
            """INSERT INTO recommended_bundles (bunch_id, day_key, stable_key, kind, activity, title, start_at, end_at,
                photo_count, uploader_count, has_group_photo, rule_trace, visible_from, last_photo_added_at,
                created_at, updated_at)
                VALUES (?, DATE '2026-10-19', ?, 'GENERAL', 'MEAL', 'New', now(), now(), 1, 1, false, '{}',
                now() - interval '1 hour', now(), now(), now()) RETURNING id""",
            Long::class.java,
            bunchId,
            UUID.randomUUID().toString().replace("-", "")
        )!!
        jdbc.update(
            "INSERT INTO recommended_bundle_photos (bundle_id, photo_id, role, card_position) " +
                "VALUES (?, ?, 'GENERAL', 1)",
            id,
            photoId
        )
        return id
    }

    private fun publish(event: Any) {
        TransactionTemplate(transactions).executeWithoutResult { events.publishEvent(event) }
    }

    private fun count(table: String, where: String): Int =
        jdbc.queryForObject("SELECT count(*) FROM $table WHERE $where", Int::class.java)!!
}
