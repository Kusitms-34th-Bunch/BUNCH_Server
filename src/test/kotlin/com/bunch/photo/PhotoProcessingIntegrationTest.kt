package com.bunch.photo

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bunch.domain.BunchRepository
import com.bunch.image.domain.ImageAsset
import com.bunch.image.domain.ImageAssetRepository
import com.bunch.photo.domain.DateSource
import com.bunch.photo.domain.Photo
import com.bunch.photo.domain.PhotoRepository
import com.bunch.photo.domain.ProcessingStatus
import com.bunch.photo.service.PhotoProcessingService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class PhotoProcessingIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var bunchesRepository: BunchRepository

    @Autowired
    private lateinit var assets: ImageAssetRepository

    @Autowired
    private lateinit var photos: PhotoRepository

    @Autowired
    private lateinit var processing: PhotoProcessingService

    private val ownedAssetIds = mutableListOf<Long>()

    @AfterEach
    fun cleanPhotoData() {
        jdbc.update("DELETE FROM photo_uploaders")
        jdbc.update("DELETE FROM photos")
        ownedAssetIds.forEach { jdbc.update("DELETE FROM image_assets WHERE id = ?", it) }
        ownedAssetIds.clear()
    }

    @Test
    fun `a pending photo becomes ready once`() {
        val photo = photo(ProcessingStatus.PENDING)

        assertThat(processing.markReady(photo.id)).isTrue()
        assertThat(statusOf(photo)).isEqualTo(ProcessingStatus.READY)
        assertThat(processing.markReady(photo.id)).isFalse()
        assertThat(statusOf(photo)).isEqualTo(ProcessingStatus.READY)
    }

    @Test
    fun `a pending photo can fail and stays failed`() {
        val photo = photo(ProcessingStatus.PENDING)

        assertThat(processing.markFailed(photo.id)).isTrue()
        assertThat(processing.markReady(photo.id)).isFalse()
        assertThat(processing.markFailed(photo.id)).isFalse()
        assertThat(statusOf(photo)).isEqualTo(ProcessingStatus.FAILED)
    }

    @Test
    fun `a ready photo cannot be failed afterwards`() {
        val photo = photo(ProcessingStatus.READY)

        assertThat(processing.markFailed(photo.id)).isFalse()
        assertThat(statusOf(photo)).isEqualTo(ProcessingStatus.READY)
    }

    @Test
    fun `a photo deleted meanwhile changes nothing`() {
        assertThat(processing.markReady(Long.MAX_VALUE)).isFalse()
        assertThat(processing.markFailed(Long.MAX_VALUE)).isFalse()
    }

    private fun statusOf(photo: Photo): ProcessingStatus = photos.findById(photo.id).orElseThrow().processingStatus

    private fun photo(status: ProcessingStatus): Photo {
        val bunchId = create(member()).id
        val asset = assets.saveAndFlush(
            ImageAsset(
                publicId = "photos/${UUID.randomUUID()}",
                url = "https://images.example/photo.jpg",
                createdAt = Instant.now()
            )
        ).also { ownedAssetIds += it.id }

        return photos.saveAndFlush(
            Photo(
                bunch = bunchesRepository.findById(bunchId).orElseThrow(),
                imageAsset = asset,
                fileHash = "0".repeat(63) + "a",
                processingStatus = status,
                capturedDate = LocalDate.of(2024, 1, 1),
                dateSource = DateSource.EXIF
            )
        )
    }
}
