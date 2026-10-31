package com.bunch.photo.service

import com.bunch.bunch.domain.BunchRepository
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.image.service.ImageAssetService
import com.bunch.photo.domain.Photo
import com.bunch.photo.domain.PhotoRepository
import com.bunch.photo.domain.PhotoUploaderRepository
import com.bunch.photo.domain.ProcessingStatus
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class PhotoService(
    private val bunches: BunchRepository,
    private val photos: PhotoRepository,
    private val uploaders: PhotoUploaderRepository,
    private val imageAssets: ImageAssetService,
    private val deletionCallbacks: ObjectProvider<PhotoFinalDeletionCallback>
) {
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

        val rows = uploaders.findAllLockedByPhotoIdOrderByIdAsc(photoId)
        val target = rows.firstOrNull { it.member.id == memberId }
            ?: return PhotoUploaderRemovalResult(photoId, uploaderRemoved = false, photoDeleted = false)

        uploaders.delete(target)
        if (rows.size > 1) {
            return PhotoUploaderRemovalResult(photoId, uploaderRemoved = true, photoDeleted = false)
        }

        deleteLocked(photo, finalDeletionCallback)
        return PhotoUploaderRemovalResult(photoId, uploaderRemoved = true, photoDeleted = true)
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

    private fun lockBunch(bunchId: Long) =
        bunches.findLocked(bunchId) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)

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
}
