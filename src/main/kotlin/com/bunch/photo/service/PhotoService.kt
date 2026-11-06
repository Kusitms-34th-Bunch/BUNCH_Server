package com.bunch.photo.service

import com.bunch.bunch.domain.Bunch
import com.bunch.bunch.domain.BunchMembership
import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.domain.MembershipRole
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.image.ImageFingerprint
import com.bunch.global.image.PhotoImage
import com.bunch.global.image.PhotoImageStorage
import com.bunch.global.image.PhotoImageUrls
import com.bunch.global.image.StoredImage
import com.bunch.image.domain.ImageAssetRepository
import com.bunch.image.service.ImageAssetService
import com.bunch.member.domain.Member
import com.bunch.member.domain.MemberRepository
import com.bunch.photo.domain.DateSource
import com.bunch.photo.domain.Photo
import com.bunch.photo.domain.PhotoImageFingerprint
import com.bunch.photo.domain.PhotoImageFingerprintRepository
import com.bunch.photo.domain.PhotoListCursor
import com.bunch.photo.domain.PhotoRepository
import com.bunch.photo.domain.PhotoUndatedCursor
import com.bunch.photo.domain.PhotoUploader
import com.bunch.photo.domain.PhotoUploaderRepository
import com.bunch.photo.domain.ProcessingStatus
import com.bunch.photo.dto.PhotoDateChangeRequest
import com.bunch.photo.dto.PhotoDateChangeResponse
import com.bunch.photo.dto.PhotoDeleteResponse
import com.bunch.photo.dto.PhotoDetailResponse
import com.bunch.photo.dto.PhotoDetailUploader
import com.bunch.photo.dto.PhotoListItem
import com.bunch.photo.dto.PhotoListResponse
import com.bunch.photo.dto.PhotoUndatedItem
import com.bunch.photo.dto.PhotoUndatedResponse
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.HexFormat

/**
 * The photos of a bunch: what the photo box screens call (upload, list, delete) and what other domains call
 * (bundle selection and connection, removing one uploader).
 *
 * Every write locks the bunch first, then the photos in ascending ID order, then the uploaders.
 */
@Service
class PhotoService(
    private val members: MemberRepository,
    private val memberships: BunchMembershipRepository,
    private val bunches: BunchRepository,
    private val photos: PhotoRepository,
    private val uploaders: PhotoUploaderRepository,
    private val fingerprints: PhotoImageFingerprintRepository,
    private val imageAssets: ImageAssetService,
    private val imageAssetRepository: ImageAssetRepository,
    private val storage: PhotoImageStorage,
    private val events: ApplicationEventPublisher,
    private val entityManager: EntityManager,
    private val deletionCallbacks: ObjectProvider<PhotoFinalDeletionCallback>,
    transactionManager: PlatformTransactionManager
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val transaction = TransactionTemplate(transactionManager)

    // ---- Selection for other domains (bundles) ----

    /**
     * Reads a complete, READY photo selection in the caller's requested order.
     * Missing, foreign-bunch, and non-READY rows reject the whole selection.
     */
    @Transactional(readOnly = true)
    fun findReadyPhotosForBunch(
        bunchId: Long,
        photoIds: Collection<Long>
    ): List<Photo> {
        val requestedIds = normalizeIds(photoIds, allowEmpty = true)
        if (requestedIds.isEmpty()) return emptyList()
        if (!bunches.existsById(bunchId)) fail(ErrorCode.RESOURCE_NOT_FOUND)

        return validateReadySelection(
            bunchId = bunchId,
            requestedIds = requestedIds,
            found = photos.findAllByIdInOrderByIdAsc(requestedIds)
        )
    }

    /**
     * Locks the bunch first and then all requested photos in ascending ID order.
     * Bundle creation or connection must use this method inside its write transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lockReadyPhotosForConnection(
        bunchId: Long,
        photoIds: Collection<Long>
    ): List<Photo> {
        val requestedIds = normalizeIds(photoIds, allowEmpty = false)
        lockBunch(bunchId)

        return validateReadySelection(
            bunchId = bunchId,
            requestedIds = requestedIds,
            found = photos.findAllLockedByIdInOrderByIdAsc(requestedIds)
        )
    }

    // ---- Upload ----

    /**
     * Saves one inspected photo into a bunch's photo box. The external upload runs outside any transaction, so this
     * method must not be called inside one: the database work is split into a check before the upload and a save
     * after it. Both lock the upload batch of the member, then the bunch, then the photo. The daily limit is counted
     * per member and per bunch, so the bunch lock alone serializes the uploads it protects. The batch limit is
     * counted per member and per batch across bunches, so it needs its own lock: two bunches never share a bunch
     * lock.
     */
    fun upload(command: PhotoUploadCommand, image: PhotoImage): PhotoUploadResult {
        // Photo rejects a time without a date, which would otherwise surface as a server error.
        if (command.capturedTime != null && command.capturedDate == null) fail(ErrorCode.INVALID_INPUT)
        val hash = sha256Hex(image.bytes)

        val shared = transaction.execute<PhotoUploadResult?> { beforeUpload(command, hash) }
        if (shared != null) return shared

        val fingerprint = ImageFingerprint.of(image.bytes)
        val file = storage.upload(image)
        val result = try {
            transaction.execute<PhotoUploadResult> { saveUpload(command, hash, file, fingerprint) }!!
        } catch (ex: Exception) {
            discardUnlessSaved(file)
            throw ex
        }
        // Someone stored the same file while this one was uploading; the new copy is not used.
        if (result.kind != PhotoUploadKind.STORED) storage.delete(file.publicId)
        return result
    }

    private fun beforeUpload(command: PhotoUploadCommand, hash: String): PhotoUploadResult? {
        val locked = lockForUpload(command)
        photos.findByBunchIdAndFileHash(command.bunchId, hash)?.let { return joinUpload(locked, command, it) }
        requireRoomForNewPhoto(command)
        return null
    }

    private fun saveUpload(
        command: PhotoUploadCommand,
        hash: String,
        file: StoredImage,
        fingerprint: Long?
    ): PhotoUploadResult {
        val locked = lockForUpload(command)
        photos.findByBunchIdAndFileHash(command.bunchId, hash)?.let { return joinUpload(locked, command, it) }
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
        fingerprint?.let { fingerprints.save(PhotoImageFingerprint(photo.id, it)) }
        uploaders.save(PhotoUploader(photo = photo, member = locked.member, uploadBatchId = command.uploadBatchId))
        locked.membership.recordActivity()
        events.publishEvent(PhotoChangedEvent(command.bunchId, photo.capturedDate, photoAdded = true))
        return PhotoUploadResult(photo.id, PhotoUploadKind.STORED)
    }

    // The existing photo is never changed; only the uploader list grows.
    private fun joinUpload(locked: UploadLocks, command: PhotoUploadCommand, existing: Photo): PhotoUploadResult {
        val photo = photos.findAllLockedByIdInOrderByIdAsc(listOf(existing.id)).singleOrNull()
            ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
        if (uploaders.existsByPhotoIdAndMemberId(photo.id, command.memberId)) {
            return PhotoUploadResult(photo.id, PhotoUploadKind.ALREADY_UPLOADED)
        }
        requireRoomForMember(command)
        uploaders.save(PhotoUploader(photo = photo, member = locked.member, uploadBatchId = command.uploadBatchId))
        locked.membership.recordActivity()
        events.publishEvent(PhotoChangedEvent(command.bunchId, photo.capturedDate, photoAdded = false))
        return PhotoUploadResult(photo.id, PhotoUploadKind.MERGED)
    }

    private fun lockForUpload(command: PhotoUploadCommand): UploadLocks {
        // Always before the bunch lock. Hash collisions only serialize unrelated batches.
        entityManager.createNativeQuery("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:batch, 0))")
            .setParameter("batch", "photo-upload-batch:${command.memberId}:${command.uploadBatchId}")
            .singleResult
        val bunch = bunches.findLocked(command.bunchId) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
        val member = members.findById(command.memberId).orElse(null)?.requireCompleted()
            ?: fail(ErrorCode.USER_NOT_FOUND)
        val membership = memberships.findByMemberIdAndBunchIdAndStatus(command.memberId, command.bunchId)
            ?: fail(ErrorCode.FORBIDDEN)
        return UploadLocks(member, bunch, membership)
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

    // ---- List ----

    // Reads only. The list is not kept in step with other members' uploads; the app asks again (3.1.1).
    @Transactional(readOnly = true)
    fun list(userId: Long, bunchId: Long, uploaderId: Long?, cursor: String?, size: Int): PhotoListResponse {
        requireListQuery(userId, bunchId, uploaderId, size)
        val after = cursor?.let(PhotoListCursor::decode)

        // One extra row tells whether another page follows.
        val position = after ?: PhotoListCursor.FIRST
        val limit = Limit.of(size + 1)
        val rows = if (uploaderId == null) {
            photos.findListPage(bunchId, position, limit)
        } else {
            photos.findListPageByUploader(bunchId, uploaderId, position, limit)
        }
        val page = rows.take(size)
        val uploaderIds = uploaderIdsOf(page.map { it.id })
        val last = page.lastOrNull()
        val nextCursor = if (rows.size > size && last != null) {
            PhotoListCursor(last.capturedDate, last.capturedTime, last.id).encode()
        } else {
            null
        }
        return PhotoListResponse(
            // Every photo of the bunch, whatever its date or state: the 1,000 photo limit counts all of them.
            totalCount = photos.countByBunchId(bunchId),
            photos = page.map {
                PhotoListItem(
                    photoId = it.id,
                    thumbnailUrl = PhotoImageUrls.thumbnail(it.imageUrl),
                    capturedDate = it.capturedDate,
                    capturedTime = it.capturedTime,
                    uploaderIds = uploaderIds[it.id].orEmpty()
                )
            },
            nextCursor = nextCursor
        )
    }

    // ---- Undated list and detail ----

    // The "photos to date" area of the photo box and of bundle creation. Same paging as the list (3.1.5, 4.1.1).
    @Transactional(readOnly = true)
    fun listUndated(userId: Long, bunchId: Long, uploaderId: Long?, cursor: String?, size: Int): PhotoUndatedResponse {
        requireListQuery(userId, bunchId, uploaderId, size)
        val after = cursor?.let(PhotoUndatedCursor::decode)

        val position = after ?: PhotoUndatedCursor.FIRST
        val limit = Limit.of(size + 1)
        val rows = if (uploaderId == null) {
            photos.findUndatedPage(bunchId, position, limit)
        } else {
            photos.findUndatedPageByUploader(bunchId, uploaderId, position, limit)
        }
        val page = rows.take(size)
        val uploaderIds = uploaderIdsOf(page.map { it.id })
        val last = page.lastOrNull()
        val nextCursor = if (rows.size > size && last != null) PhotoUndatedCursor(last.id).encode() else null
        // The "N" of the area follows the member filter (3.1.2) and not the page.
        val totalCount = if (uploaderId == null) {
            photos.countUndated(bunchId)
        } else {
            photos.countUndatedByUploader(bunchId, uploaderId)
        }
        return PhotoUndatedResponse(
            totalCount = totalCount,
            photos = page.map {
                PhotoUndatedItem(
                    photoId = it.id,
                    thumbnailUrl = PhotoImageUrls.thumbnail(it.imageUrl),
                    uploaderIds = uploaderIds[it.id].orEmpty()
                )
            },
            nextCursor = nextCursor
        )
    }

    // A photo that is gone, of another bunch or not ready is not found (3.1.3 and 3.5.1 exceptions).
    @Transactional(readOnly = true)
    fun getDetail(userId: Long, bunchId: Long, photoId: Long): PhotoDetailResponse {
        requireMember(userId, bunchId)
        val photo = photos.findDetail(bunchId, photoId) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
        return PhotoDetailResponse(
            photoId = photo.id,
            imageUrl = photo.imageUrl,
            capturedDate = photo.capturedDate,
            uploaders = photos.findUploaderProfiles(photoId)
                .map { PhotoDetailUploader(it.memberId, it.nickname, it.imageUrl) }
        )
    }

    // The checks both lists start with: the caller, the page size and the member filter.
    private fun requireListQuery(userId: Long, bunchId: Long, uploaderId: Long?, size: Int) {
        requireMember(userId, bunchId)
        if (size !in 1..MAX_SIZE) fail(ErrorCode.INVALID_INPUT)
        // A filter on someone who is not a member could never be picked from the chips.
        if (uploaderId != null && !isMember(uploaderId, bunchId)) fail(ErrorCode.INVALID_INPUT)
    }

    // The uploaders of each photo in the order they uploaded, from one query for the whole page.
    private fun uploaderIdsOf(photoIds: List<Long>): Map<Long, List<Long>> =
        if (photoIds.isEmpty()) {
            emptyMap()
        } else {
            photos.findUploaderIdsByPhotoIds(photoIds).groupBy({ it.photoId }, { it.memberId })
        }

    private fun requireMember(userId: Long, bunchId: Long) {
        members.findById(userId).orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }.requireCompleted()
        if (!bunches.existsById(bunchId)) fail(ErrorCode.RESOURCE_NOT_FOUND)
        if (!isMember(userId, bunchId)) fail(ErrorCode.FORBIDDEN)
    }

    private fun isMember(memberId: Long, bunchId: Long) =
        memberships.findByMemberIdAndBunchIdAndStatus(memberId, bunchId) != null

    // ---- Delete ----

    /**
     * Deletes the photos a member picked in the photo box. The whole selection succeeds or fails together:
     * one photo the caller may not delete rejects the request before anything is removed.
     */
    @Transactional
    fun deletePhotos(userId: Long, bunchId: Long, photoIds: List<Long>): PhotoDeleteResponse {
        val selection = lockSelection(userId, bunchId, photoIds)
        val ownPhotoIds = requireManageable(selection, userId)

        var deleted = 0
        var uploaderRemoved = 0
        selection.photos.forEach { photo ->
            if (photo.id !in ownPhotoIds) {
                if (deletePhoto(bunchId, photo.id)) deleted++
                return@forEach
            }
            val result = removeUploader(bunchId, photo.id, userId)
            if (result.photoDeleted) deleted++ else if (result.uploaderRemoved) uploaderRemoved++
        }
        return PhotoDeleteResponse(deleted, uploaderRemoved)
    }

    @Transactional
    fun removeUploader(
        bunchId: Long,
        photoId: Long,
        memberId: Long
    ): PhotoUploaderRemovalResult =
        removeUploader(bunchId, photoId, memberId, PhotoFinalDeletionCallback.NONE)

    /**
     * Removes one uploader. The callback runs immediately before the final Photo delete,
     * in this transaction, so a future bundle owner can remove draft references first.
     */
    @Transactional
    fun removeUploader(
        bunchId: Long,
        photoId: Long,
        memberId: Long,
        finalDeletionCallback: PhotoFinalDeletionCallback
    ): PhotoUploaderRemovalResult {
        lockBunch(bunchId)
        val photo = photos.findAllLockedByIdInOrderByIdAsc(listOf(photoId)).singleOrNull()
            ?: return PhotoUploaderRemovalResult(photoId, uploaderRemoved = false, photoDeleted = false)

        if (photo.bunch.id != bunchId) fail(ErrorCode.FORBIDDEN)

        return removeLockedUploader(photo, memberId, finalDeletionCallback)
    }

    /**
     * Removes every uploader row for one member while keeping the bunch and all photo locks
     * in the contract's order. The caller owns the surrounding membership transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun removeAllUploaders(
        bunchId: Long,
        memberId: Long
    ): List<PhotoUploaderRemovalResult> {
        lockBunch(bunchId)
        return photos
            .findAllLockedByBunchIdAndUploaderMemberIdOrderByIdAsc(bunchId, memberId)
            .map { removeLockedUploader(it, memberId, PhotoFinalDeletionCallback.NONE) }
    }

    private fun removeLockedUploader(
        photo: Photo,
        memberId: Long,
        finalDeletionCallback: PhotoFinalDeletionCallback
    ): PhotoUploaderRemovalResult {

        val rows = uploaders.findAllLockedByPhotoIdOrderByIdAsc(photo.id)
        val target = rows.firstOrNull { it.member.id == memberId }
            ?: return PhotoUploaderRemovalResult(photo.id, uploaderRemoved = false, photoDeleted = false)

        uploaders.delete(target)
        announceChange(photo.bunch.id, photo.capturedDate)
        if (rows.size > 1) {
            return PhotoUploaderRemovalResult(photo.id, uploaderRemoved = true, photoDeleted = false)
        }

        deleteLocked(photo, finalDeletionCallback)
        return PhotoUploaderRemovalResult(photo.id, uploaderRemoved = true, photoDeleted = true)
    }

    /**
     * Deletes the photo together with every uploader, as an administrator does with a photo they did not upload.
     * Returns false when the photo no longer exists, so a repeated request changes nothing.
     */
    @Transactional
    fun deletePhoto(bunchId: Long, photoId: Long): Boolean {
        lockBunch(bunchId)
        val photo = photos.findAllLockedByIdInOrderByIdAsc(listOf(photoId)).singleOrNull() ?: return false

        if (photo.bunch.id != bunchId) fail(ErrorCode.FORBIDDEN)

        uploaders.deleteAll(uploaders.findAllLockedByPhotoIdOrderByIdAsc(photoId))
        uploaders.flush()
        announceChange(bunchId, photo.capturedDate)
        deleteLocked(photo, PhotoFinalDeletionCallback.NONE)
        return true
    }

    private fun deleteLocked(photo: Photo, finalDeletionCallback: PhotoFinalDeletionCallback) {
        deletionCallbacks.orderedStream().forEach { it.beforePhotoDelete(photo.id) }
        finalDeletionCallback.beforePhotoDelete(photo.id)
        val assetId = photo.imageAsset.id
        photos.delete(photo)
        photos.flush()
        imageAssets.releaseAfterCommit(assetId)
    }

    // ---- Change the date ----

    /**
     * Sets the capture date of the photos a member picked, or clears it (3.4.1, 3.4.2). The whole selection succeeds
     * or fails together. The time of a photo stays: only the date changes, and the source becomes the user.
     */
    @Transactional
    fun changeDate(userId: Long, bunchId: Long, request: PhotoDateChangeRequest): PhotoDateChangeResponse {
        val newDate = request.capturedDate
        // Exactly one of the two, so a request that leaves both out cannot clear a date by accident.
        if ((newDate != null) == request.clearDate) fail(ErrorCode.INVALID_INPUT)
        if (newDate != null && newDate.isAfter(PhotoUploadLimits.today(Instant.now()))) fail(ErrorCode.INVALID_INPUT)

        val selection = lockSelection(userId, bunchId, request.photoIds)
        requireManageable(selection, userId)
        // Only a date the user set can be taken back. A shooting date or a time is never cleared by a request.
        if (newDate == null && selection.photos.any { it.dateSource != DateSource.USER || it.capturedTime != null }) {
            fail(ErrorCode.INVALID_INPUT)
        }

        val ids = selection.photos.map { it.id }
        // Read before the update: the dates the photos leave are the dates whose recommendations must be recalculated.
        val leftDates = selection.photos.mapNotNull { it.capturedDate }.toSet()
        if (newDate == null) photos.clearCapturedDate(ids) else photos.setCapturedDate(ids, newDate)
        (leftDates + listOfNotNull(newDate)).forEach { announceChange(bunchId, it) }
        return PhotoDateChangeResponse(ids.size)
    }

    // ---- Processing status ----

    /** Returns true only when this call moved the photo from PENDING to READY. */
    @Transactional
    fun markReady(photoId: Long): Boolean = transition(photoId, ProcessingStatus.READY)

    /** Returns true only when this call moved the photo from PENDING to FAILED. */
    @Transactional
    fun markFailed(photoId: Long): Boolean = transition(photoId, ProcessingStatus.FAILED)

    // READY and FAILED are final. A repeated call, or a photo deleted meanwhile, changes nothing.
    private fun transition(photoId: Long, to: ProcessingStatus): Boolean =
        photos.transitionStatus(photoId, ProcessingStatus.PENDING, to) == 1

    // ---- Shared ----

    private fun lockBunch(bunchId: Long) =
        bunches.findLocked(bunchId) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)

    private class LockedSelection(val photos: List<Photo>, val administrator: Boolean)

    /**
     * What a write on picked photos starts with: the caller and the bunch, then the photos locked in ID order.
     * A selection with a photo that is already gone runs nothing (3.3.1): the app drops the named photos from its
     * selection and asks again.
     */
    private fun lockSelection(userId: Long, bunchId: Long, photoIds: List<Long>): LockedSelection {
        if (photoIds.isEmpty() || photoIds.size > MAX_PHOTOS_PER_REQUEST) fail(ErrorCode.INVALID_INPUT)
        if (photoIds.any { it <= 0L } || photoIds.distinct().size != photoIds.size) fail(ErrorCode.INVALID_INPUT)

        members.findById(userId).orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }.requireCompleted()
        // Bunch first, then photos in ascending ID order: the same order every other photo write uses.
        lockBunch(bunchId)
        val membership = memberships.findByMemberIdAndBunchIdAndStatus(userId, bunchId) ?: fail(ErrorCode.FORBIDDEN)

        val found = photos.findAllLockedByIdInOrderByIdAsc(photoIds)
        if (found.any { it.bunch.id != bunchId || it.processingStatus != ProcessingStatus.READY }) {
            fail(ErrorCode.INVALID_INPUT)
        }
        val foundIds = found.map { it.id }.toSet()
        val gone = photoIds.filter { it !in foundIds }
        if (gone.isNotEmpty()) {
            throw BusinessException(ErrorCode.PHOTO_SELECTION_CHANGED, mapOf("missingPhotoIds" to gone))
        }
        return LockedSelection(found, isAdministrator(membership.role))
    }

    /**
     * An uploader may change what they uploaded and an administrator anything. One photo the caller may not touch
     * rejects the request. Returns the IDs of the photos the caller uploaded.
     */
    private fun requireManageable(selection: LockedSelection, userId: Long): Set<Long> {
        val uploadedByCaller = photos.findUploaderIdsByPhotoIds(selection.photos.map { it.id })
            .filter { it.memberId == userId }
            .map { it.photoId }
            .toSet()
        if (!selection.administrator && uploadedByCaller.size != selection.photos.size) fail(ErrorCode.FORBIDDEN)
        return uploadedByCaller
    }

    /**
     * Tells the recommended bundles that what a date is made of changed: a photo or an uploader left, or a photo moved
     * to or from the date (4.2.9). No photo was added, so the "last photo added" time of a recommendation must not
     * move. A photo without a date is part of no recommendation (4.2.7), so it is not announced.
     * Every way to remove a photo or an uploader goes through here, so a new way to delete cannot forget it.
     */
    private fun announceChange(bunchId: Long, date: LocalDate?) {
        if (date != null) events.publishEvent(PhotoChangedEvent(bunchId, date, photoAdded = false))
    }

    // A co-administrator has the same photo rights (5.6.2). Add that role here when it exists.
    private fun isAdministrator(role: MembershipRole) = role == MembershipRole.OWNER

    private fun validateReadySelection(
        bunchId: Long,
        requestedIds: List<Long>,
        found: List<Photo>
    ): List<Photo> {
        if (found.size != requestedIds.size) fail(ErrorCode.INVALID_INPUT)
        if (found.any { it.bunch.id != bunchId || it.processingStatus != ProcessingStatus.READY }) {
            fail(ErrorCode.INVALID_INPUT)
        }

        val byId = found.associateBy(Photo::id)
        if (requestedIds.any { it !in byId }) fail(ErrorCode.INVALID_INPUT)
        return requestedIds.map { byId[it]!! }
    }

    private fun normalizeIds(photoIds: Collection<Long>, allowEmpty: Boolean): List<Long> {
        val ids = photoIds.toList()
        if (!allowEmpty && ids.isEmpty()) fail(ErrorCode.INVALID_INPUT)
        if (ids.any { it <= 0L } || ids.distinct().size != ids.size) fail(ErrorCode.INVALID_INPUT)
        return ids
    }

    private fun fail(errorCode: ErrorCode): Nothing = throw BusinessException(errorCode)

    private class UploadLocks(val member: Member, val bunch: Bunch, val membership: BunchMembership)

    companion object {
        const val DEFAULT_SIZE = 60
        const val MAX_SIZE = 100
        const val MAX_PHOTOS_PER_REQUEST = 50
    }
}
