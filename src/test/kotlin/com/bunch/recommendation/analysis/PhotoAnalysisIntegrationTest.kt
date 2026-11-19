package com.bunch.recommendation.analysis

import com.bunch.global.job.AsyncJobWorker
import com.bunch.photo.domain.PhotoRecoFeatureRepository
import com.bunch.recommendation.RecommendationIntegrationSupport
import com.bunch.recommendation.domain.analysis.PhotoAnalysisFailure
import com.bunch.recommendation.domain.analysis.PhotoAnalysisFailure.Reason
import com.bunch.recommendation.domain.combination.ActivityCode
import com.bunch.recommendation.domain.combination.AiAnalysis
import com.bunch.recommendation.domain.combination.AiStatus
import com.bunch.recommendation.domain.combination.EventClueCode
import com.bunch.recommendation.domain.combination.PeopleCount
import com.bunch.recommendation.domain.combination.PhotoSubject
import com.bunch.recommendation.domain.combination.Reading
import com.bunch.recommendation.service.RecoPhotoInputBuilder
import com.bunch.recommendation.service.analysis.PhotoAnalysisService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.sql.Timestamp
import java.time.Instant

/**
 * The AI analysis of a photo from the request to the combination input (recommended bundle specification 4-2, 4-3), on
 * the real database and the real job queue. The analyzer and the loader are scripted, so no photo leaves the test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PhotoAnalysisFakesConfig::class)
class PhotoAnalysisIntegrationTest : RecommendationIntegrationSupport() {
    @Autowired
    private lateinit var analysis: PhotoAnalysisService

    @Autowired
    private lateinit var worker: AsyncJobWorker

    @Autowired
    private lateinit var inputs: RecoPhotoInputBuilder

    @Autowired
    private lateinit var features: PhotoRecoFeatureRepository

    @Autowired
    private lateinit var analyzer: ScriptedPhotoAnalyzer

    @Autowired
    private lateinit var loader: ScriptedImageLoader

    @BeforeEach
    fun resetScript() {
        analyzer.reset()
        loader.loaded.clear()
    }

    @AfterEach
    fun cleanJobs() {
        jdbc.update("DELETE FROM async_jobs WHERE job_type = ?", PhotoAnalysisService.JOB_TYPE)
    }

    @Test
    fun `a request marks the photo waiting and puts one job in the queue`() {
        val photoId = photo()

        assertThat(analysis.request(photoId)).isTrue()

        assertThat(statusOf(photoId)).isEqualTo("PENDING")
        assertThat(jobs(photoId)).hasSize(1)
        assertThat(jobs(photoId).single()["status"]).isEqualTo("PENDING")
    }

    @Test
    fun `asking again while the job is in the queue puts no second job`() {
        val photoId = photo()
        analysis.request(photoId)

        assertThat(analysis.request(photoId)).isFalse()

        assertThat(jobs(photoId)).hasSize(1)
    }

    @Test
    fun `the job makes the analysis and stores the answer, the model and the time`() {
        val photoId = photo()
        analysis.request(photoId)

        worker.runOnce()

        val row = featureOf(photoId)
        assertThat(row["ai_status"]).isEqualTo("DONE")
        assertThat(row["ai_model_version"]).isEqualTo(ScriptedPhotoAnalyzer.MODEL_VERSION)
        assertThat(row["analyzed_at"]).isNotNull()
        assertThat(mapper.readTree(row["raw"] as String)).isEqualTo(mapper.readTree(ScriptedPhotoAnalyzer.ANSWER))
        assertThat(jobs(photoId).single()["status"]).isEqualTo("SUCCEEDED")
        assertThat(loader.loaded).containsExactly(IMAGE_URL)
    }

    @Test
    fun `the stored answer is what the combination reads of the photo`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photoId = photo(bunchId, ownerId)
        analysis.request(photoId)
        worker.runOnce()

        val read = inputs.build(bunchId).single()

        assertThat(read.aiStatus).isEqualTo(AiStatus.DONE)
        assertThat(read.analysis.clues).containsExactly(Reading(EventClueCode.TENT, 0.9))
        assertThat(read.analysis.activity).isEqualTo(Reading(ActivityCode.OUTDOOR, 0.8))
        assertThat(read.analysis.subject).isEqualTo(PhotoSubject.LANDSCAPE)
        assertThat(read.analysis.people).isEqualTo(PeopleCount.P0)
    }

    @Test
    fun `a failure that asking again cannot mend makes the photo a failed one and ends the job`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photoId = photo(bunchId, ownerId)
        analysis.request(photoId)
        analyzer.failure = PhotoAnalysisFailure(Reason.REFUSED)

        worker.runOnce()

        assertThat(statusOf(photoId)).isEqualTo("FAILED")
        assertThat(featureOf(photoId)["raw"]).isNull()
        assertThat(jobs(photoId).single()["status"]).isEqualTo("SUCCEEDED")
        val read = inputs.build(bunchId).single()
        assertThat(read.aiStatus).isEqualTo(AiStatus.FAILED)
        assertThat(read.analysis).isEqualTo(AiAnalysis.NONE)
    }

    @Test
    fun `a failure that asking again can mend leaves the photo waiting and the queue asks again`() {
        val photoId = photo()
        analysis.request(photoId)
        analyzer.failure = PhotoAnalysisFailure(Reason.RATE_LIMITED)

        worker.runOnce()

        assertThat(statusOf(photoId)).isEqualTo("PENDING")
        val job = jobs(photoId).single()
        assertThat(job["status"]).isEqualTo("PENDING")
        assertThat(job["attempts"]).isEqualTo(1)
        assertThat((job["next_attempt_at"] as Timestamp).toInstant()).isAfter(Instant.now())
    }

    @Test
    fun `when the model answers at the next attempt the photo is done`() {
        val photoId = photo()
        analysis.request(photoId)
        analyzer.failure = PhotoAnalysisFailure(Reason.UNAVAILABLE)
        worker.runOnce()
        analyzer.failure = null

        worker.runOnce(Instant.now().plusSeconds(3600))

        assertThat(statusOf(photoId)).isEqualTo("DONE")
        assertThat(analyzer.calls).isEqualTo(2)
    }

    @Test
    fun `a photo that is done is left as it is when the job runs again`() {
        val photoId = photo()
        analysis.request(photoId)
        analysis.analyze(photoId)

        analysis.analyze(photoId)

        assertThat(analyzer.calls).isEqualTo(1)
        assertThat(loader.loaded).hasSize(1)
    }

    @Test
    fun `a photo that is gone has nothing to analyze`() {
        analysis.analyze(Long.MAX_VALUE)

        assertThat(analyzer.calls).isEqualTo(0)
    }

    @Test
    fun `a photo that is done is not asked for again`() {
        val photoId = photo()
        analysis.request(photoId)
        analysis.analyze(photoId)

        assertThat(analysis.request(photoId)).isFalse()
        assertThat(statusOf(photoId)).isEqualTo("DONE")
    }

    @Test
    fun `a failed photo is asked for again and starts over`() {
        val photoId = photo()
        features.markFailed(photoId, Instant.now())

        assertThat(analysis.request(photoId)).isTrue()

        assertThat(statusOf(photoId)).isEqualTo("PENDING")
    }

    @Test
    fun `a late answer replaces a failure but a failure never replaces an answer`() {
        val photoId = photo()
        features.markFailed(photoId, Instant.now())
        features.markDone(photoId, "late", ScriptedPhotoAnalyzer.ANSWER, Instant.now())
        assertThat(statusOf(photoId)).isEqualTo("DONE")

        features.markFailed(photoId, Instant.now())

        assertThat(statusOf(photoId)).isEqualTo("DONE")
        assertThat(featureOf(photoId)["ai_model_version"]).isEqualTo("late")
    }

    @Test
    fun `an answer is stored for a photo that had no row`() {
        val photoId = photo()

        features.markDone(photoId, "m", ScriptedPhotoAnalyzer.ANSWER, Instant.now())

        assertThat(statusOf(photoId)).isEqualTo("DONE")
    }

    @Test
    fun `a photo that waits longer than the waiting time is read as a failed one`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val old = photo(bunchId, ownerId)
        val recent = photo(bunchId, ownerId)
        analysis.request(old)
        analysis.request(recent)
        setCreatedAt(old, Instant.now().minusSeconds(31 * 60))
        setCreatedAt(recent, Instant.now().minusSeconds(29 * 60))

        val read = inputs.build(bunchId).associateBy { it.photoId }

        assertThat(read.getValue(old).aiStatus).isEqualTo(AiStatus.FAILED)
        assertThat(read.getValue(recent).aiStatus).isEqualTo(AiStatus.PENDING)
    }

    @Test
    fun `a stored answer that cannot be read makes the photo a failed one`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photoId = photo(bunchId, ownerId)
        features.markDone(photoId, "m", "[]", Instant.now())

        val read = inputs.build(bunchId).single()

        assertThat(read.aiStatus).isEqualTo(AiStatus.FAILED)
        assertThat(read.analysis).isEqualTo(AiAnalysis.NONE)
    }

    @Test
    fun `every failure that asking again cannot mend makes the photo a failed one`() {
        listOf(Reason.REFUSED, Reason.BAD_REQUEST, Reason.INVALID_RESPONSE).forEach { reason ->
            val photoId = photo()
            analysis.request(photoId)
            analyzer.failure = PhotoAnalysisFailure(reason)

            worker.runOnce()

            assertThat(statusOf(photoId)).describedAs(reason.name).isEqualTo("FAILED")
        }
    }

    private fun photo(): Long {
        val (ownerId, bunchId) = bunchWithOwner()
        return photo(bunchId, ownerId)
    }

    private fun photo(bunchId: Long, ownerId: Long): Long =
        insertPhoto(bunchId, "2026-10-19", "13:30:00", uploaders = listOf(ownerId))

    private fun statusOf(photoId: Long): String? = jdbc.query(
        "SELECT ai_status FROM photo_reco_feature WHERE photo_id = ?",
        { rs, _ -> rs.getString(1) },
        photoId
    ).firstOrNull()

    private fun featureOf(photoId: Long): Map<String, Any?> = jdbc.queryForMap(
        """SELECT ai_status, ai_model_version, CAST(ai_raw AS TEXT) AS raw, analyzed_at
            FROM photo_reco_feature WHERE photo_id = ?""",
        photoId
    )

    private fun jobs(photoId: Long): List<Map<String, Any?>> = jdbc.queryForList(
        "SELECT status, attempts, next_attempt_at FROM async_jobs WHERE job_type = ? AND target_key = ?",
        PhotoAnalysisService.JOB_TYPE,
        photoId.toString()
    )

    private fun setCreatedAt(photoId: Long, at: Instant) {
        jdbc.update("UPDATE photos SET created_at = ? WHERE id = ?", Timestamp.from(at), photoId)
    }

    companion object {
        const val IMAGE_URL = "https://res.cloudinary.com/test/image/upload/test-http/photo.jpg"
    }
}
