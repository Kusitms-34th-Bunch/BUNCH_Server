package com.bunch.recommendation

import com.bunch.recommendation.domain.combination.AiAnalysis
import com.bunch.recommendation.domain.combination.AiStatus
import com.bunch.recommendation.domain.combination.SourceType
import com.bunch.recommendation.domain.combination.TimeState
import com.bunch.recommendation.service.RecoPhotoInputBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

/** The input of the recommended bundle combination made from the photos of a bunch (specification 3-1, 5-1, 5-2). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecoPhotoInputIntegrationTest : RecommendationIntegrationSupport() {
    @Autowired
    private lateinit var inputs: RecoPhotoInputBuilder

    @Test
    fun `a photo with a date and a time carries its date, time, uploader and what is not known yet`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photoId = insertPhoto(bunchId, "2026-10-19", "13:30:00", uploaders = listOf(ownerId))

        val photo = inputs.build(bunchId).single()

        assertThat(photo.photoId).isEqualTo(photoId)
        assertThat(photo.capturedDate).isEqualTo(LocalDate.of(2026, 10, 19))
        assertThat(photo.capturedTime).isEqualTo(LocalTime.of(13, 30))
        assertThat(photo.timeState).isEqualTo(TimeState.FULL)
        assertThat(photo.uploads.map { it.memberId }).containsExactly(ownerId)
        assertThat(photo.uploadedAt).isEqualTo(createdAtOf(photoId))
        assertThat(photo.quality).isNull()
        assertThat(photo.analysis).isEqualTo(AiAnalysis.NONE)
        assertThat(photo.embedding).isNull()
    }

    @Test
    fun `a photo not analyzed is a failed analysis so that it does not hold the day back`() {
        val (ownerId, bunchId) = bunchWithOwner()
        insertPhoto(bunchId, "2026-10-19", "13:30:00", uploaders = listOf(ownerId))

        assertThat(inputs.build(bunchId).single().aiStatus).isEqualTo(AiStatus.FAILED)
    }

    @Test
    fun `a photo uploaded together is one photo with every uploader and upload batch`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val other = joined(ownerId, bunchId)
        insertPhoto(bunchId, "2026-10-19", "13:30:00", uploaders = listOf(ownerId, other))

        val photo = inputs.build(bunchId).single()

        assertThat(photo.uploads.map { it.memberId }).containsExactly(ownerId, other)
        assertThat(photo.uploads.map { it.uploadBatchId }.toSet()).hasSize(2)
    }

    @Test
    fun `a photo with a date only and a photo without a date are input with the time they have`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val dateOnly = insertPhoto(bunchId, "2026-10-19", uploaders = listOf(ownerId))
        val unknown = insertPhoto(bunchId, null, uploaders = listOf(ownerId))

        val photos = inputs.build(bunchId).associateBy { it.photoId }

        assertThat(photos.getValue(dateOnly).timeState).isEqualTo(TimeState.DATE_ONLY)
        assertThat(photos.getValue(unknown).timeState).isEqualTo(TimeState.UNKNOWN)
        assertThat(photos.getValue(unknown).capturedDate).isNull()
    }

    @Test
    fun `photos not ready and photos of other bunches are not input`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherBunchId = create(member()).id
        val shown = insertPhoto(bunchId, "2026-10-19", "13:30:00", uploaders = listOf(ownerId))
        insertPhoto(bunchId, "2026-10-19", status = "PENDING", uploaders = listOf(ownerId))
        insertPhoto(bunchId, "2026-10-19", status = "FAILED", uploaders = listOf(ownerId))
        insertPhoto(otherBunchId, "2026-10-19", uploaders = listOf(ownerId))

        assertThat(inputs.build(bunchId).map { it.photoId }).containsExactly(shown)
    }

    @Test
    fun `photos of a draft bundle and of a published bundle are not input`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val inDraft = insertPhoto(bunchId, "2026-10-19", "13:30:00", uploaders = listOf(ownerId))
        val inPublished = insertPhoto(bunchId, "2026-10-19", "13:40:00", uploaders = listOf(ownerId))
        val free = insertPhoto(bunchId, "2026-10-19", "13:50:00", uploaders = listOf(ownerId))
        bundleWith(bunchId, ownerId, "DRAFT", inDraft)
        bundleWith(bunchId, ownerId, "PUBLISHED", inPublished)

        assertThat(inputs.build(bunchId).map { it.photoId }).containsExactly(free)
    }

    @Test
    fun `a photo of a deleted draft is input again`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photoId = insertPhoto(bunchId, "2026-10-19", "13:30:00", uploaders = listOf(ownerId))
        val bundleId = bundleWith(bunchId, ownerId, "DRAFT", photoId)
        assertThat(inputs.build(bunchId)).isEmpty()

        deleteBundle(bundleId)

        assertThat(inputs.build(bunchId).map { it.photoId }).containsExactly(photoId)
    }

    @Test
    fun `the offset of the place becomes the offset and a photo without one has none`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val seoul = insertPhoto(bunchId, "2026-10-19", "13:30:00", uploaders = listOf(ownerId))
        val newYork = insertPhoto(bunchId, "2026-10-19", "13:40:00", uploaders = listOf(ownerId))
        val none = insertPhoto(bunchId, "2026-10-19", "13:50:00", uploaders = listOf(ownerId))
        setOffset(seoul, "+09:00")
        setOffset(newYork, "-05:00")

        val photos = inputs.build(bunchId).associateBy { it.photoId }

        assertThat(photos.getValue(seoul).utcOffset).isEqualTo(ZoneOffset.ofHours(9))
        assertThat(photos.getValue(newYork).utcOffset).isEqualTo(ZoneOffset.ofHours(-5))
        assertThat(photos.getValue(none).utcOffset).isNull()
    }

    @Test
    fun `the original type the app told is kept whatever the photo has`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val camera = insertPhoto(bunchId, null, uploaders = listOf(ownerId))
        val screenshot = insertPhoto(bunchId, "2026-10-19", "13:30:00", uploaders = listOf(ownerId))
        val saved = insertPhoto(bunchId, "2026-10-19", "13:40:00", uploaders = listOf(ownerId))
        setSource(camera, "CAMERA")
        setSource(screenshot, "SCREENSHOT")
        setSource(saved, "SAVED")

        val photos = inputs.build(bunchId).associateBy { it.photoId }

        assertThat(photos.getValue(camera).source).isEqualTo(SourceType.CAMERA)
        assertThat(photos.getValue(screenshot).source).isEqualTo(SourceType.SCREENSHOT)
        assertThat(photos.getValue(saved).source).isEqualTo(SourceType.SAVED)
    }

    @Test
    fun `a photo uploaded before the app told its type is read by its shooting time`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val timed = insertPhoto(bunchId, "2026-10-19", "13:30:00", uploaders = listOf(ownerId))
        val dateOnly = insertPhoto(bunchId, "2026-10-19", uploaders = listOf(ownerId))
        val undated = insertPhoto(bunchId, null, uploaders = listOf(ownerId))
        val noType = insertPhoto(bunchId, "2026-10-19", "14:00:00", uploaders = listOf(ownerId))
        jdbc.update("INSERT INTO photo_reco_feature (photo_id, source) VALUES (?, NULL)", noType)

        val photos = inputs.build(bunchId).associateBy { it.photoId }

        assertThat(photos.getValue(timed).source).isEqualTo(SourceType.CAMERA)
        assertThat(photos.getValue(noType).source).isEqualTo(SourceType.CAMERA)
        assertThat(photos.getValue(dateOnly).source).isEqualTo(SourceType.SAVED)
        assertThat(photos.getValue(undated).source).isEqualTo(SourceType.SAVED)
    }

    @Test
    fun `the fingerprint and the date batch of a photo are carried`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val withBoth = insertPhoto(bunchId, "2026-10-20", dateSource = "USER", uploaders = listOf(ownerId))
        val without = insertPhoto(bunchId, "2026-10-19", "13:30:00", uploaders = listOf(ownerId))
        val batch = UUID.randomUUID()
        jdbc.update("UPDATE photos SET date_batch_id = CAST(? AS uuid) WHERE id = ?", batch.toString(), withBoth)
        jdbc.update(
            "INSERT INTO photo_image_fingerprints (photo_id, fingerprint, created_at) VALUES (?, ?, now())",
            withBoth,
            -1234567890123L
        )

        val photos = inputs.build(bunchId).associateBy { it.photoId }

        assertThat(photos.getValue(withBoth).fingerprint).isEqualTo(-1234567890123L)
        assertThat(photos.getValue(withBoth).dateBatchId).isEqualTo(batch)
        assertThat(photos.getValue(without).fingerprint).isNull()
        assertThat(photos.getValue(without).dateBatchId).isNull()
    }

    @Test
    fun `the photos come in the order of their numbers whatever their time`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val late = insertPhoto(bunchId, "2026-10-19", "23:00:00", uploaders = listOf(ownerId))
        val early = insertPhoto(bunchId, "2026-10-19", "08:00:00", uploaders = listOf(ownerId))
        val undated = insertPhoto(bunchId, null, uploaders = listOf(ownerId))

        assertThat(inputs.build(bunchId).map { it.photoId }).containsExactly(late, early, undated)
    }

    @Test
    fun `a bunch without photos has no input`() {
        val (_, bunchId) = bunchWithOwner()

        assertThat(inputs.build(bunchId)).isEmpty()
    }

    private fun setOffset(photoId: Long, offset: String) {
        jdbc.update("UPDATE photos SET captured_utc_offset = ? WHERE id = ?", offset, photoId)
    }

    private fun setSource(photoId: Long, source: String) {
        jdbc.update("INSERT INTO photo_reco_feature (photo_id, source) VALUES (?, ?)", photoId, source)
    }

    private fun createdAtOf(photoId: Long) = jdbc.queryForObject(
        "SELECT created_at FROM photos WHERE id = ?",
        java.sql.Timestamp::class.java,
        photoId
    )!!.toInstant()
}
