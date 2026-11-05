package com.bunch.recommendation

import com.bunch.photo.PhotoHttpTestSupport
import com.bunch.recommendation.service.RecommendationService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MaterialPhotoIntegrationTest : PhotoHttpTestSupport() {
    @Autowired
    private lateinit var recommendation: RecommendationService

    @AfterEach
    fun cleanMaterialFixtures() {
        jdbc.update("DELETE FROM bundle_photos")
        jdbc.update("DELETE FROM bundles")
        jdbc.update("DELETE FROM photo_uploaders")
        jdbc.update("DELETE FROM photos")
        jdbc.update("DELETE FROM image_assets WHERE public_id LIKE 'test-http/%'")
    }

    @Test
    fun `a ready dated photo is material with its date time and uploader`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photoId = insertPhoto(bunchId, "2026-10-19", "13:30:00", uploaders = listOf(ownerId))

        val material = recommendation.materialPhotos(bunchId).single()

        assertThat(material.photoId).isEqualTo(photoId)
        assertThat(material.capturedDate).isEqualTo(LocalDate.of(2026, 10, 19))
        assertThat(material.capturedTime).isEqualTo(LocalTime.of(13, 30))
        assertThat(material.uploaderIds).containsExactly(ownerId)
        assertThat(material.batchIds).hasSize(1)
        assertThat(material.fingerprint).isNull()
    }

    @Test
    fun `a dated photo without a time and a photo the user dated are material`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val untimed = insertPhoto(bunchId, "2026-10-19", uploaders = listOf(ownerId))
        val userDated = insertPhoto(bunchId, "2026-10-20", dateSource = "USER", uploaders = listOf(ownerId))

        val material = recommendation.materialPhotos(bunchId)

        assertThat(material.map { it.photoId }).containsExactlyInAnyOrder(untimed, userDated)
        assertThat(material.first { it.photoId == untimed }.capturedTime).isNull()
    }

    @Test
    fun `photos without a date photos not ready and photos of other bunches are not material`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherBunchId = create(member()).id
        val shown = insertPhoto(bunchId, "2026-10-19", uploaders = listOf(ownerId))
        insertPhoto(bunchId, null, uploaders = listOf(ownerId))
        insertPhoto(bunchId, "2026-10-19", status = "PENDING", uploaders = listOf(ownerId))
        insertPhoto(bunchId, "2026-10-19", status = "FAILED", uploaders = listOf(ownerId))
        insertPhoto(otherBunchId, "2026-10-19", uploaders = listOf(ownerId))

        assertThat(recommendation.materialPhotos(bunchId).map { it.photoId }).containsExactly(shown)
    }

    @Test
    fun `a photo used in a draft bundle is not material`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val used = insertPhoto(bunchId, "2026-10-19", uploaders = listOf(ownerId))
        val free = insertPhoto(bunchId, "2026-10-19", uploaders = listOf(ownerId))
        bundleWith(bunchId, ownerId, "DRAFT", used)

        assertThat(recommendation.materialPhotos(bunchId).map { it.photoId }).containsExactly(free)
    }

    @Test
    fun `a photo used in a published bundle is not material`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val used = insertPhoto(bunchId, "2026-10-19", uploaders = listOf(ownerId))
        val free = insertPhoto(bunchId, "2026-10-19", uploaders = listOf(ownerId))
        bundleWith(bunchId, ownerId, "PUBLISHED", used)

        assertThat(recommendation.materialPhotos(bunchId).map { it.photoId }).containsExactly(free)
    }

    @Test
    fun `a photo of a deleted draft is material again`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photoId = insertPhoto(bunchId, "2026-10-19", uploaders = listOf(ownerId))
        val bundleId = bundleWith(bunchId, ownerId, "DRAFT", photoId)
        assertThat(recommendation.materialPhotos(bunchId)).isEmpty()

        jdbc.update("DELETE FROM bundles WHERE id = ?", bundleId)

        assertThat(recommendation.materialPhotos(bunchId).map { it.photoId }).containsExactly(photoId)
    }

    @Test
    fun `a bundle photo whose photo was deleted does not hide other photos`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val gone = insertPhoto(bunchId, "2026-10-19", uploaders = listOf(ownerId))
        val free = insertPhoto(bunchId, "2026-10-19", uploaders = listOf(ownerId))
        bundleWith(bunchId, ownerId, "PUBLISHED", gone)
        jdbc.update("DELETE FROM photo_uploaders WHERE photo_id = ?", gone)
        jdbc.update("DELETE FROM photos WHERE id = ?", gone)

        assertThat(jdbc.queryForObject("SELECT count(*) FROM bundle_photos WHERE photo_id IS NULL", Long::class.java))
            .isEqualTo(1L)
        assertThat(recommendation.materialPhotos(bunchId).map { it.photoId }).containsExactly(free)
    }

    @Test
    fun `a photo uploaded together is one material with every uploader and upload batch`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val other = joined(ownerId, bunchId)
        val photoId = insertPhoto(bunchId, "2026-10-19", "13:30:00", uploaders = listOf(ownerId, other))

        val material = recommendation.materialPhotos(bunchId).single()

        assertThat(material.photoId).isEqualTo(photoId)
        assertThat(material.uploaderIds).containsExactlyInAnyOrder(ownerId, other)
        assertThat(material.batchIds).hasSize(2)
    }

    @Test
    fun `a bunch without photos has no material`() {
        val (_, bunchId) = bunchWithOwner()

        assertThat(recommendation.materialPhotos(bunchId)).isEmpty()
    }

    // A bundle that uses the photos, inserted straight into the tables so a test does not need the bundle flow.
    private fun bundleWith(bunchId: Long, creatorId: Long, status: String, vararg photoIds: Long): Long {
        val bundleId = jdbc.queryForObject(
            """INSERT INTO bundles (bunch_id, created_by, status, created_at, updated_at, published_at)
                VALUES (?, ?, ?, now(), now(), CASE WHEN ? = 'PUBLISHED' THEN now() END) RETURNING id""",
            Long::class.java,
            bunchId,
            creatorId,
            status,
            status
        )!!
        photoIds.forEachIndexed { position, photoId ->
            jdbc.update(
                """INSERT INTO bundle_photos (bundle_id, photo_id, image_asset_id, position, added_by, added_at)
                    SELECT ?, id, image_asset_id, ?, ?, now() FROM photos WHERE id = ?""",
                bundleId,
                position,
                creatorId,
                photoId
            )
        }
        return bundleId
    }
}
