package com.bunch.photo.service

import com.bunch.bunch.domain.Bunch
import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bunch.domain.BunchRepository
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.image.PhotoImage
import com.bunch.global.image.PhotoImageStorage
import com.bunch.global.image.StoredImage
import com.bunch.image.domain.ImageAssetRepository
import com.bunch.image.service.ImageAssetService
import com.bunch.member.domain.Member
import com.bunch.member.domain.MemberRepository
import com.bunch.photo.domain.DateSource
import com.bunch.photo.domain.Photo
import com.bunch.photo.domain.PhotoRepository
import com.bunch.photo.domain.PhotoUploader
import com.bunch.photo.domain.PhotoUploaderRepository
import com.bunch.photo.domain.ProcessingStatus
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

/**
 * Saves one inspected photo into a bunch's photo box. The external upload runs outside any
 * transaction, so the database work is split into a check before it and a save after it.
 * Both lock the bunch first and then the photo. The daily limit is counted per member and per bunch, so the
 * bunch lock alone serializes the uploads it protects and no member lock is needed.
 */
@Service
class PhotoUploadService(
    private val members: MemberRepository,
    private val bunches: BunchRepository,
    private val memberships: BunchMembershipRepository,
    private val photos: PhotoRepository,
    private val uploaders: PhotoUploaderRepository,
    private val imageAssets: ImageAssetService,
    private val imageAssetRepository: ImageAssetRepository,
    private val storage: PhotoImageStorage,
    private val events: ApplicationEventPublisher,
    transactionManager: PlatformTransactionManager
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val transaction = TransactionTemplate(transactionManager)

    fun upload(command: PhotoUploadCommand, image: PhotoImage): PhotoUploadResult {
        // Photo rejects a time without a date, which would otherwise surface as a server error.
        if (command.capturedTime != null && command.capturedDate == null) fail(ErrorCode.INVALID_INPUT)
        val hash = sha256Hex(image.bytes)

        val shared = transaction.execute<PhotoUploadResult?> { beforeUpload(command, hash) }
        if (shared != null) return shared

        val file = storage.upload(image)
        val result = try {
            transaction.execute<PhotoUploadResult> { save(command, hash, file) }!!
        } catch (ex: Exception) {
            discardUnlessSaved(file)
            throw ex
        }
        // Someone stored the same file while this one was uploading; the new copy is not used.
        if (result.kind != PhotoUploadKind.STORED) storage.delete(file.publicId)
        return result
    }

    private fun beforeUpload(command: PhotoUploadCommand, hash: String): PhotoUploadResult? {
        val locked = lock(command)
        photos.findByBunchIdAndFileHash(command.bunchId, hash)?.let { return join(locked, command, it) }
        requireRoomForNewPhoto(command)
        return null
    }

    private fun save(command: PhotoUploadCommand, hash: String, file: StoredImage): PhotoUploadResult {
        val locked = lock(command)
        photos.findByBunchIdAndFileHash(command.bunchId, hash)?.let { return join(locked, command, it) }
        requireRoomForNewPhoto(command)

        val photo = photos.save(
            Photo(
                bunch = locked.bunch,
                imageAsset = imageAssets.store(file),
                fileHash = hash,
                processingStatus = ProcessingStatus.READY,
                capturedDate = command.capturedDate,
                capturedTime = command.capturedTime,
                dateSource = if (command.capturedDate == null) DateSource.UNKNOWN else DateSource.EXIF
            )
        )
        uploaders.save(PhotoUploader(photo = photo, member = locked.member, uploadBatchId = command.uploadBatchId))
        events.publishEvent(PhotoChangedEvent(command.bunchId, photo.capturedDate, photoAdded = true))
        return PhotoUploadResult(photo.id, PhotoUploadKind.STORED)
    }

    // The existing photo is never changed; only the uploader list grows.
    private fun join(locked: Locked, command: PhotoUploadCommand, existing: Photo): PhotoUploadResult {
        val photo = photos.findAllLockedByIdInOrderByIdAsc(listOf(existing.id)).singleOrNull()
            ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
        if (uploaders.existsByPhotoIdAndMemberId(photo.id, command.memberId)) {
            return PhotoUploadResult(photo.id, PhotoUploadKind.ALREADY_UPLOADED)
        }
        requireRoomForMember(command)
        uploaders.save(PhotoUploader(photo = photo, member = locked.member, uploadBatchId = command.uploadBatchId))
        events.publishEvent(PhotoChangedEvent(command.bunchId, photo.capturedDate, photoAdded = false))
        return PhotoUploadResult(photo.id, PhotoUploadKind.MERGED)
    }

    private fun lock(command: PhotoUploadCommand): Locked {
        val bunch = bunches.findLocked(command.bunchId) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
        val member = members.findById(command.memberId).orElse(null)?.requireCompleted()
            ?: fail(ErrorCode.USER_NOT_FOUND)
        memberships.findByMemberIdAndBunchIdAndStatus(command.memberId, command.bunchId) ?: fail(ErrorCode.FORBIDDEN)
        return Locked(member, bunch)
    }

    private fun requireRoomForNewPhoto(command: PhotoUploadCommand) {
        if (photos.countByBunchId(command.bunchId) >= PhotoUploadLimits.MAX_PHOTOS_PER_BUNCH) {
            fail(ErrorCode.PHOTO_BUNCH_LIMIT_EXCEEDED)
        }
        requireRoomForMember(command)
    }

    private fun requireRoomForMember(command: PhotoUploadCommand) {
        val today = PhotoUploadLimits.startOfUploadDay(Instant.now())
        if (uploaders.countByMemberInBunchSince(command.memberId, command.bunchId, today) >=
            PhotoUploadLimits.MAX_PHOTOS_PER_MEMBER_PER_BUNCH_PER_DAY
        ) {
            fail(ErrorCode.PHOTO_DAILY_LIMIT_EXCEEDED)
        }
        if (uploaders.countByMemberIdAndUploadBatchId(command.memberId, command.uploadBatchId) >=
            PhotoUploadLimits.MAX_PHOTOS_PER_BATCH
        ) {
            fail(ErrorCode.PHOTO_BATCH_LIMIT_EXCEEDED)
        }
    }

    // A failed commit can still have succeeded, so the file is removed only when no asset row owns it.
    private fun discardUnlessSaved(file: StoredImage) {
        try {
            if (!imageAssetRepository.existsByPublicId(file.publicId)) storage.delete(file.publicId)
        } catch (_: RuntimeException) {
            logger.warn("Uploaded photo file cleanup skipped: publicId={}", file.publicId)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun fail(errorCode: ErrorCode): Nothing = throw BusinessException(errorCode)

    private class Locked(val member: Member, val bunch: Bunch)
}
