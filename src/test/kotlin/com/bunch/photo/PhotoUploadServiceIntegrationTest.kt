package com.bunch.photo

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.photo.service.PhotoChangedEvent
import com.bunch.photo.service.PhotoService
import com.bunch.photo.service.PhotoUploadKind
import com.bunch.photo.service.PhotoUploadLimits
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
@RecordApplicationEvents
class PhotoUploadServiceIntegrationTest : PhotoUploadTestSupport() {
    @Autowired
    private lateinit var photoService: PhotoService

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var applicationEvents: ApplicationEvents

    @Test
    fun `stores a new photo as READY with its uploader batch and capture info`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val batchId = UUID.randomUUID()
        val photo = image("first")

        val result = uploads.upload(
            command(bunchId, ownerId, batchId, LocalDate.of(2026, 10, 3), LocalTime.of(14, 5, 9)),
            photo
        )

        assertThat(result.kind).isEqualTo(PhotoUploadKind.STORED)
        val row = jdbc.queryForMap(
            """SELECT p.bunch_id, p.file_hash, p.processing_status, p.captured_date, p.captured_time,
                p.date_source, a.public_id FROM photos p JOIN image_assets a ON a.id = p.image_asset_id
                WHERE p.id = ?""",
            result.photoId
        )
        assertThat(row["bunch_id"]).isEqualTo(bunchId)
        assertThat(row["file_hash"]).isEqualTo(hashOf(photo))
        assertThat(row["processing_status"]).isEqualTo("READY")
        assertThat(row["captured_date"].toString()).isEqualTo("2026-10-03")
        assertThat(row["captured_time"].toString()).isEqualTo("14:05:09")
        assertThat(row["date_source"]).isEqualTo("EXIF")
        assertThat(row["public_id"].toString()).startsWith("photos/test-")
        val uploader = jdbc.queryForMap(
            "SELECT member_id, upload_batch_id FROM photo_uploaders WHERE photo_id = ?",
            result.photoId
        )
        assertThat(uploader["member_id"]).isEqualTo(ownerId)
        assertThat(uploader["upload_batch_id"].toString()).isEqualTo(batchId.toString())
        verify(storage, times(1)).upload(anyImage())
        assertThat(changeEvents())
            .containsExactly(PhotoChangedEvent(bunchId, LocalDate.of(2026, 10, 3), photoAdded = true))
    }

    @Test
    fun `a photo without capture info is stored as UNKNOWN and a date alone is allowed`() {
        val (ownerId, bunchId) = bunchWithOwner()

        val undated = uploads.upload(command(bunchId, ownerId), image("undated"))
        val dateOnly = uploads.upload(command(bunchId, ownerId, capturedDate = LocalDate.of(2026, 1, 2)), image("d"))

        val first = jdbc.queryForMap(
            "SELECT captured_date, captured_time, date_source FROM photos WHERE id = ?",
            undated.photoId
        )
        assertThat(first["captured_date"]).isNull()
        assertThat(first["captured_time"]).isNull()
        assertThat(first["date_source"]).isEqualTo("UNKNOWN")
        val second = jdbc.queryForMap(
            "SELECT captured_date, captured_time, date_source FROM photos WHERE id = ?",
            dateOnly.photoId
        )
        assertThat(second["captured_time"]).isNull()
        assertThat(second["date_source"]).isEqualTo("EXIF")
    }

    @Test
    fun `a capture time without a date is rejected before anything is uploaded`() {
        val (ownerId, bunchId) = bunchWithOwner()

        assertError(ErrorCode.INVALID_INPUT) {
            uploads.upload(command(bunchId, ownerId, capturedTime = LocalTime.NOON), image("time-only"))
        }

        assertThat(photoCount(bunchId)).isZero()
        verifyNoInteractions(storage)
    }

    @Test
    fun `the same file from another member is merged without a second upload or a new photo`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherId = joined(ownerId, bunchId)
        val photo = image("shared")
        val first = uploads.upload(command(bunchId, ownerId, capturedDate = LocalDate.of(2026, 5, 1)), photo)

        val merged = uploads.upload(command(bunchId, otherId, capturedDate = LocalDate.of(2026, 6, 6)), photo)

        assertThat(merged.kind).isEqualTo(PhotoUploadKind.MERGED)
        assertThat(merged.photoId).isEqualTo(first.photoId)
        assertThat(photoCount(bunchId)).isEqualTo(1)
        assertThat(uploaderCount(first.photoId)).isEqualTo(2)
        verify(storage, times(1)).upload(anyImage())
        val keptDate = jdbc.queryForObject(
            "SELECT captured_date::text FROM photos WHERE id = ?",
            String::class.java,
            first.photoId
        )
        assertThat(keptDate).isEqualTo("2026-05-01")
        // Only the first upload brings a new photo; the merge changes the uploaders but not the photo count.
        assertThat(changeEvents().map { it.photoAdded }).containsExactly(true, false)
    }

    @Test
    fun `a file I already uploaded is excluded and returns the existing photo`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photo = image("mine")
        val first = uploads.upload(command(bunchId, ownerId), photo)

        val again = uploads.upload(command(bunchId, ownerId), photo)

        assertThat(again.kind).isEqualTo(PhotoUploadKind.ALREADY_UPLOADED)
        assertThat(again.photoId).isEqualTo(first.photoId)
        assertThat(uploaderCount(first.photoId)).isEqualTo(1)
        verify(storage, times(1)).upload(anyImage())
        assertThat(changeEvents()).hasSize(1)
    }

    @Test
    fun `the same file in another bunch is a separate photo`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherBunchId = create(ownerId).id
        val photo = image("two-bunches")

        val first = uploads.upload(command(bunchId, ownerId), photo)
        val second = uploads.upload(command(otherBunchId, ownerId), photo)

        assertThat(first.kind).isEqualTo(PhotoUploadKind.STORED)
        assertThat(second.kind).isEqualTo(PhotoUploadKind.STORED)
        assertThat(second.photoId).isNotEqualTo(first.photoId)
    }

    @Test
    fun `non members members who left and unfinished signups cannot upload`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val outsiderId = member()
        val leaverId = joined(ownerId, bunchId)
        jdbc.update("UPDATE bunch_memberships SET status = 'LEFT' WHERE member_id = ?", leaverId)
        val unfinishedId = member(completed = false)

        assertError(ErrorCode.FORBIDDEN) { uploads.upload(command(bunchId, outsiderId), image("a")) }
        assertError(ErrorCode.FORBIDDEN) { uploads.upload(command(bunchId, leaverId), image("b")) }
        assertError(ErrorCode.SIGNUP_INCOMPLETE) { uploads.upload(command(bunchId, unfinishedId), image("c")) }
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { uploads.upload(command(Long.MAX_VALUE, ownerId), image("d")) }

        assertThat(photoCount(bunchId)).isZero()
        verifyNoInteractions(storage)
    }

    @Test
    fun `a full bunch rejects a new photo before uploading but still merges a shared one`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherId = joined(ownerId, bunchId)
        val shared = image("already-in-the-box")
        val existingId = insertPhoto(bunchId, hashOf(shared), uploaderId = ownerId)
        seedPhotos(bunchId, PhotoUploadLimits.MAX_PHOTOS_PER_BUNCH - 1)

        assertError(ErrorCode.PHOTO_BUNCH_LIMIT_EXCEEDED) { uploads.upload(command(bunchId, otherId), image("new")) }
        verifyNoInteractions(storage)

        val merged = uploads.upload(command(bunchId, otherId), shared)
        assertThat(merged.kind).isEqualTo(PhotoUploadKind.MERGED)
        assertThat(merged.photoId).isEqualTo(existingId)
        assertThat(photoCount(bunchId)).isEqualTo(PhotoUploadLimits.MAX_PHOTOS_PER_BUNCH.toLong())
    }

    @Test
    fun `990 photos plus 20 stores 10 and rejects 10`() {
        val (ownerId, bunchId) = bunchWithOwner()
        seedPhotos(bunchId, 990)

        val outcomes = (1..20).map { index ->
            runCatching { uploads.upload(command(bunchId, ownerId), image("bulk-$index")) }
        }

        assertThat(outcomes.count { it.isSuccess }).isEqualTo(10)
        assertThat(outcomes.filter { it.isFailure }.map { (it.exceptionOrNull() as BusinessException).errorCode })
            .containsOnly(ErrorCode.PHOTO_BUNCH_LIMIT_EXCEEDED)
        assertThat(photoCount(bunchId)).isEqualTo(1000)
    }

    @Test
    fun `the daily limit counts today in Korean time and ignores earlier days`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val startOfDay = PhotoUploadLimits.startOfUploadDay(Instant.now())
        val dailyLimit = PhotoUploadLimits.MAX_PHOTOS_PER_MEMBER_PER_DAY
        seedPhotos(bunchId, dailyLimit, ownerId, startOfDay.minus(Duration.ofHours(1)))

        val yesterdayDoesNotCount = uploads.upload(command(bunchId, ownerId), image("today-1"))
        assertThat(yesterdayDoesNotCount.kind).isEqualTo(PhotoUploadKind.STORED)

        jdbc.update("DELETE FROM photo_uploaders")
        jdbc.update("DELETE FROM photos")
        seedPhotos(bunchId, dailyLimit, ownerId, startOfDay)

        assertError(ErrorCode.PHOTO_DAILY_LIMIT_EXCEEDED) {
            uploads.upload(command(bunchId, ownerId), image("today-2"))
        }
    }

    @Test
    fun `deleting uploads frees the daily limit`() {
        val (ownerId, bunchId) = bunchWithOwner()
        seedPhotos(bunchId, PhotoUploadLimits.MAX_PHOTOS_PER_MEMBER_PER_DAY, ownerId)
        assertError(ErrorCode.PHOTO_DAILY_LIMIT_EXCEEDED) { uploads.upload(command(bunchId, ownerId), image("over")) }

        photoService.removeUploader(
            bunchId,
            jdbc.queryForObject("SELECT min(photo_id) FROM photo_uploaders", Long::class.java)!!,
            ownerId
        )

        assertThat(uploads.upload(command(bunchId, ownerId), image("room-again")).kind)
            .isEqualTo(PhotoUploadKind.STORED)
    }

    @Test
    fun `an upload batch holds at most 50 photos but a retry of a stored photo still succeeds`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val batchId = UUID.randomUUID()
        val first = image("batch-first")
        val stored = uploads.upload(command(bunchId, ownerId, batchId), first)
        seedPhotos(bunchId, PhotoUploadLimits.MAX_PHOTOS_PER_BATCH - 1, ownerId, batchId = batchId)

        assertError(ErrorCode.PHOTO_BATCH_LIMIT_EXCEEDED) {
            uploads.upload(command(bunchId, ownerId, batchId), image("batch-51st"))
        }
        val retried = uploads.upload(command(bunchId, ownerId, batchId), first)
        val otherBatch = uploads.upload(command(bunchId, ownerId), image("batch-other"))

        assertThat(retried.kind).isEqualTo(PhotoUploadKind.ALREADY_UPLOADED)
        assertThat(retried.photoId).isEqualTo(stored.photoId)
        assertThat(otherBatch.kind).isEqualTo(PhotoUploadKind.STORED)
    }

    @Test
    fun `a storage failure leaves nothing behind and is reported as retryable`() {
        val (ownerId, bunchId) = bunchWithOwner()
        `when`(storage.upload(anyImage())).thenThrow(BusinessException(ErrorCode.PHOTO_IMAGE_STORAGE_FAILED))

        assertError(ErrorCode.PHOTO_IMAGE_STORAGE_FAILED) { uploads.upload(command(bunchId, ownerId), image("x")) }

        assertThat(photoCount(bunchId)).isZero()
        assertThat(count("photo_uploaders")).isZero()
        assertThat(count("image_assets WHERE public_id LIKE 'photos/test-%'")).isZero()
        verify(storage, never()).delete(org.mockito.ArgumentMatchers.anyString())
    }

    @Test
    fun `leaving the bunch during the upload stops the save and deletes the uploaded file`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val memberId = joined(ownerId, bunchId)
        val uploadedFile = stored("photos/test-left-during-upload")
        `when`(storage.upload(anyImage())).thenAnswer {
            jdbc.update("UPDATE bunch_memberships SET status = 'LEFT' WHERE member_id = ?", memberId)
            uploadedFile
        }

        assertError(ErrorCode.FORBIDDEN) { uploads.upload(command(bunchId, memberId), image("leaver")) }

        assertThat(photoCount(bunchId)).isZero()
        assertThat(count("image_assets WHERE public_id = 'photos/test-left-during-upload'")).isZero()
        verify(storage, times(1)).delete(uploadedFile.publicId)
    }

    @Test
    fun `a same file stored by someone else during the upload is merged and the new file is deleted`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherId = joined(ownerId, bunchId)
        val shared = image("raced")
        val uploadedFile = stored("photos/test-raced-upload")
        var winnerPhotoId = 0L
        `when`(storage.upload(anyImage())).thenAnswer {
            winnerPhotoId = insertPhoto(bunchId, hashOf(shared), uploaderId = otherId)
            uploadedFile
        }

        val result = uploads.upload(command(bunchId, ownerId), shared)

        assertThat(result.kind).isEqualTo(PhotoUploadKind.MERGED)
        assertThat(result.photoId).isEqualTo(winnerPhotoId)
        assertThat(photoCount(bunchId)).isEqualTo(1)
        assertThat(uploaderCount(winnerPhotoId)).isEqualTo(2)
        verify(storage, times(1)).delete(uploadedFile.publicId)
    }

    @Test
    fun `a stored photo is accepted by the bundle connection lock`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val result = uploads.upload(command(bunchId, ownerId), image("for-bundle"))

        val selected = TransactionTemplate(transactionManager).execute {
            photoService.lockReadyPhotosForConnection(bunchId, listOf(result.photoId))
        }

        assertThat(selected!!.map { it.id }).containsExactly(result.photoId)
    }

    private fun changeEvents(): List<PhotoChangedEvent> =
        applicationEvents.stream(PhotoChangedEvent::class.java).toList()
}
