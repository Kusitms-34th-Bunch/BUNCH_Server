package com.bunch.photo

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bunch.domain.BunchRepository
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.image.ProfileImageStorage
import com.bunch.image.domain.ImageAsset
import com.bunch.image.domain.ImageAssetRepository
import com.bunch.member.domain.MemberRepository
import com.bunch.photo.domain.DateSource
import com.bunch.photo.domain.Photo
import com.bunch.photo.domain.PhotoRepository
import com.bunch.photo.domain.PhotoUploader
import com.bunch.photo.domain.PhotoUploaderRepository
import com.bunch.photo.domain.ProcessingStatus
import com.bunch.photo.service.PhotoFinalDeletionCallback
import com.bunch.photo.service.PhotoService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.Collections
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class PhotoIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var bunchesRepository: BunchRepository

    @Autowired
    private lateinit var membersRepository: MemberRepository

    @Autowired
    private lateinit var assets: ImageAssetRepository

    @Autowired
    private lateinit var photos: PhotoRepository

    @Autowired
    private lateinit var uploaders: PhotoUploaderRepository

    @Autowired
    private lateinit var photoService: PhotoService

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @MockitoBean
    private lateinit var storage: ProfileImageStorage

    private val ownedAssetIds = mutableListOf<Long>()

    @AfterEach
    fun cleanPhotoData() {
        jdbc.update("DELETE FROM photo_uploaders")
        jdbc.update("DELETE FROM photos")
        ownedAssetIds.forEach { jdbc.update("DELETE FROM image_assets WHERE id = ?", it) }
        ownedAssetIds.clear()
    }

    @Test
    fun `selection preserves requested order and rejects an incomplete ready set`() {
        val bunchId = bunch()
        val first = photo(bunchId, hash = hash('a'))
        val second = photo(bunchId, hash = hash('b'))
        val failed = photo(bunchId, hash = hash('c'), status = ProcessingStatus.FAILED)

        assertThat(photoService.findReadyPhotosForBunch(bunchId, listOf(second.id, first.id)).map { it.id })
            .containsExactly(second.id, first.id)
        assertThatThrownBy {
            photoService.findReadyPhotosForBunch(bunchId, listOf(first.id, failed.id))
        }.isInstanceOf(BusinessException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.INVALID_INPUT)
    }

    @Test
    fun `selection rejects missing and foreign photos as one request`() {
        val firstBunchId = bunch()
        val secondBunchId = bunch()
        val own = photo(firstBunchId, hash = hash('d'))
        val foreign = photo(secondBunchId, hash = hash('e'))

        assertThatThrownBy {
            photoService.findReadyPhotosForBunch(firstBunchId, listOf(own.id, foreign.id))
        }.isInstanceOf(BusinessException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.INVALID_INPUT)
        assertThatThrownBy {
            photoService.findReadyPhotosForBunch(firstBunchId, listOf(own.id, Long.MAX_VALUE))
        }.isInstanceOf(BusinessException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.INVALID_INPUT)
    }

    @Test
    fun `connection lock requires a caller write transaction`() {
        val bunchId = bunch()
        val stored = photo(bunchId, hash = hash('f'))

        assertThatThrownBy {
            photoService.lockReadyPhotosForConnection(bunchId, listOf(stored.id))
        }.isInstanceOf(IllegalTransactionStateException::class.java)

        val locked = TransactionTemplate(transactionManager).execute {
            photoService.lockReadyPhotosForConnection(bunchId, listOf(stored.id))
        }
        assertThat(locked!!.map { it.id }).containsExactly(stored.id)

        val foreignBunchId = bunch()
        val foreign = photo(foreignBunchId, hash = hash('b'))
        assertThatThrownBy {
            TransactionTemplate(transactionManager).execute {
                photoService.lockReadyPhotosForConnection(bunchId, listOf(stored.id, foreign.id))
            }
        }.isInstanceOf(BusinessException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.INVALID_INPUT)
        assertThatThrownBy {
            TransactionTemplate(transactionManager).execute {
                photoService.lockReadyPhotosForConnection(bunchId, listOf(stored.id, Long.MAX_VALUE))
            }
        }.isInstanceOf(BusinessException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.INVALID_INPUT)
    }

    @Test
    fun `removing one of several uploaders keeps the photo`() {
        val ownerId = member()
        val otherMemberId = member()
        val photo = photo(bunch(), hash = hash('1'))
        uploader(photo, ownerId)
        uploader(photo, otherMemberId)

        val result = photoService.removeUploader(photo.bunch.id, photo.id, ownerId)

        assertThat(result.uploaderRemoved).isTrue()
        assertThat(result.photoDeleted).isFalse()
        assertThat(photos.existsById(photo.id)).isTrue()
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM photo_uploaders WHERE photo_id = ?",
            Long::class.java,
            photo.id
        )).isEqualTo(1L)
    }

    @Test
    fun `removing the final uploader invokes callback and releases the image asset after commit`() {
        val memberId = member()
        val photo = photo(bunch(), hash = hash('2'))
        uploader(photo, memberId)
        val callbackPhotoIds = mutableListOf<Long>()

        val result = photoService.removeUploader(
            photo.bunch.id,
            photo.id,
            memberId,
            PhotoFinalDeletionCallback { callbackPhotoIds += it }
        )

        assertThat(result.photoDeleted).isTrue()
        assertThat(callbackPhotoIds).containsExactly(photo.id)
        assertThat(photos.existsById(photo.id)).isFalse()
        assertThat(assets.existsById(photo.imageAsset.id)).isFalse()
        verify(storage, times(1)).delete(photo.imageAsset.publicId)
    }

    @Test
    fun `callback failure rolls back final uploader and photo deletion`() {
        val memberId = member()
        val photo = photo(bunch(), hash = hash('7'))
        uploader(photo, memberId)

        assertThatThrownBy {
            photoService.removeUploader(
                photo.bunch.id,
                photo.id,
                memberId,
                PhotoFinalDeletionCallback { throw IllegalStateException("bundle cleanup failed") }
            )
        }.isInstanceOf(IllegalStateException::class.java)

        assertThat(photos.existsById(photo.id)).isTrue()
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM photo_uploaders WHERE photo_id = ?",
            Long::class.java,
            photo.id
        )).isEqualTo(1L)
        assertThat(assets.existsById(photo.imageAsset.id)).isTrue()
        verifyNoInteractions(storage)
    }

    @Test
    fun `concurrent final uploader removals delete photo and asset once`() {
        val firstMemberId = member()
        val secondMemberId = member()
        val photo = photo(bunch(), hash = hash('8'))
        uploader(photo, firstMemberId)
        uploader(photo, secondMemberId)
        val callbackPhotoIds = Collections.synchronizedList(mutableListOf<Long>())

        val results = concurrently(
            listOf(
                {
                    photoService.removeUploader(
                        photo.bunch.id,
                        photo.id,
                        firstMemberId,
                        PhotoFinalDeletionCallback { callbackPhotoIds += it }
                    )
                },
                {
                    photoService.removeUploader(
                        photo.bunch.id,
                        photo.id,
                        secondMemberId,
                        PhotoFinalDeletionCallback { callbackPhotoIds += it }
                    )
                }
            )
        )

        assertThat(results.all { it.isSuccess }).isTrue()
        val removalResults = results.map { it.getOrThrow() }
        assertThat(removalResults.count { it.uploaderRemoved }).isEqualTo(2)
        assertThat(removalResults.count { it.photoDeleted }).isEqualTo(1)
        assertThat(callbackPhotoIds).containsExactly(photo.id)
        assertThat(photos.existsById(photo.id)).isFalse()
        verify(storage, times(1)).delete(photo.imageAsset.publicId)
    }

    @Test
    fun `missing uploader is idempotent and leaves the photo unchanged`() {
        val uploaderMemberId = member()
        val otherMemberId = member()
        val photo = photo(bunch(), hash = hash('3'))
        uploader(photo, uploaderMemberId)

        val result = photoService.removeUploader(photo.bunch.id, photo.id, otherMemberId)

        assertThat(result.uploaderRemoved).isFalse()
        assertThat(result.photoDeleted).isFalse()
        assertThat(uploaders.findAll()).hasSize(1)
    }

    @Test
    fun `photo metadata and uploader upload data round trip through postgres`() {
        val memberId = member()
        val bunchId = create(memberId).id
        val createdAt = Instant.parse("2024-01-01T00:00:00.123Z")
        val capturedDate = LocalDate.of(2024, 1, 2)
        val capturedTime = LocalTime.of(3, 4, 5)
        val batchId = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val uploadedAt = Instant.parse("2024-01-02T03:04:05.123Z")
        val savedPhoto = photos.saveAndFlush(
            Photo(
                bunch = bunchesRepository.findById(bunchId).orElseThrow(),
                imageAsset = asset(),
                fileHash = hash('9'),
                processingStatus = ProcessingStatus.READY,
                capturedDate = capturedDate,
                capturedTime = capturedTime,
                dateSource = DateSource.EXIF,
                createdAt = createdAt
            )
        )
        val savedUploader = uploaders.saveAndFlush(
            PhotoUploader(
                photo = savedPhoto,
                member = membersRepository.findById(memberId).orElseThrow(),
                uploadBatchId = batchId,
                uploadedAt = uploadedAt
            )
        )
        entityManager.clear()

        val reloadedPhoto = photos.findById(savedPhoto.id).orElseThrow()
        val reloadedUploader = uploaders.findById(savedUploader.id).orElseThrow()
        assertThat(reloadedPhoto.processingStatus).isEqualTo(ProcessingStatus.READY)
        assertThat(reloadedPhoto.capturedDate).isEqualTo(capturedDate)
        assertThat(reloadedPhoto.capturedTime).isEqualTo(capturedTime)
        assertThat(reloadedPhoto.dateSource).isEqualTo(DateSource.EXIF)
        assertThat(reloadedPhoto.createdAt).isEqualTo(createdAt)
        assertThat(reloadedUploader.photo.id).isEqualTo(savedPhoto.id)
        assertThat(reloadedUploader.member.id).isEqualTo(memberId)
        assertThat(reloadedUploader.uploadBatchId).isEqualTo(batchId)
        assertThat(reloadedUploader.uploadedAt).isEqualTo(uploadedAt)
    }

    @Test
    fun `photo date and hash invariants are enforced before persistence`() {
        val bunchId = bunch()
        val asset = asset()
        val bunch = bunchesRepository.findById(bunchId).orElseThrow()

        assertThatThrownBy {
            Photo(bunch = bunch, imageAsset = asset, fileHash = "A".repeat(64))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            Photo(
                bunch = bunch,
                imageAsset = asset,
                fileHash = hash('4'),
                capturedTime = LocalTime.NOON
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            Photo(
                bunch = bunch,
                imageAsset = asset,
                fileHash = hash('5'),
                dateSource = DateSource.UNKNOWN,
                capturedDate = LocalDate.of(2024, 1, 1)
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            Photo(
                bunch = bunch,
                imageAsset = asset,
                fileHash = hash('6'),
                dateSource = DateSource.USER
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun bunch(): Long {
        val memberId = member()
        return create(memberId).id
    }

    private fun asset(): ImageAsset = assets.saveAndFlush(
        ImageAsset(
            publicId = "photos/${UUID.randomUUID()}",
            url = "https://images.example/photo.jpg",
            createdAt = Instant.now()
        )
    ).also { ownedAssetIds += it.id }

    private fun photo(
        bunchId: Long,
        hash: String,
        status: ProcessingStatus = ProcessingStatus.READY
    ): Photo {
        val saved = photos.saveAndFlush(
            Photo(
                bunch = bunchesRepository.findById(bunchId).orElseThrow(),
                imageAsset = asset(),
                fileHash = hash,
                processingStatus = status,
                capturedDate = LocalDate.of(2024, 1, 1),
                capturedTime = LocalTime.NOON,
                dateSource = DateSource.USER
            )
        )
        return saved
    }

    private fun uploader(
        photo: Photo,
        memberId: Long,
        uploadBatchId: UUID = UUID.randomUUID(),
        uploadedAt: Instant = Instant.now()
    ): PhotoUploader = uploaders.saveAndFlush(
        PhotoUploader(
            photo = photo,
            member = membersRepository.findById(memberId).orElseThrow(),
            uploadBatchId = uploadBatchId,
            uploadedAt = uploadedAt
        )
    )

    private fun hash(last: Char): String = "0".repeat(63) + last
}
