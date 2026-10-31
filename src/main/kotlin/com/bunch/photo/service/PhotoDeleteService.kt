package com.bunch.photo.service

import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.domain.MembershipRole
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.member.domain.MemberRepository
import com.bunch.photo.domain.PhotoRepository
import com.bunch.photo.domain.PhotoUploaderRepository
import com.bunch.photo.domain.ProcessingStatus
import com.bunch.photo.dto.PhotoDeleteResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Deletes the photos a member picked in the photo box. The whole selection succeeds or fails together:
 * one photo the caller may not delete rejects the request before anything is removed.
 */
@Service
class PhotoDeleteService(
    private val members: MemberRepository,
    private val memberships: BunchMembershipRepository,
    private val bunches: BunchRepository,
    private val photos: PhotoRepository,
    private val uploaders: PhotoUploaderRepository,
    private val photoService: PhotoService
) {
    @Transactional
    fun delete(userId: Long, bunchId: Long, photoIds: List<Long>): PhotoDeleteResponse {
        if (photoIds.isEmpty() || photoIds.size > MAX_PHOTOS_PER_REQUEST) fail(ErrorCode.INVALID_INPUT)
        if (photoIds.any { it <= 0L } || photoIds.distinct().size != photoIds.size) fail(ErrorCode.INVALID_INPUT)

        members.findById(userId).orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }.requireCompleted()
        // Bunch first, then photos in ascending ID order: the same order every other photo write uses.
        bunches.findLocked(bunchId) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
        val membership = memberships.findByMemberIdAndBunchIdAndStatus(userId, bunchId) ?: fail(ErrorCode.FORBIDDEN)
        val administrator = membership.role == MembershipRole.OWNER

        // A photo that is already gone is skipped, so a retry after a lost response is harmless.
        val found = photos.findAllLockedByIdInOrderByIdAsc(photoIds)
        if (found.any { it.bunch.id != bunchId || it.processingStatus != ProcessingStatus.READY }) {
            fail(ErrorCode.INVALID_INPUT)
        }

        val uploaderIds = uploaders.findAllByPhotoIdInOrderByPhotoIdAndIdAsc(found.map { it.id })
            .groupBy({ it.photo.id }, { it.member.id })
        val ownPhotoIds = found.filter { userId in uploaderIds[it.id].orEmpty() }.map { it.id }.toSet()
        if (!administrator && ownPhotoIds.size != found.size) fail(ErrorCode.FORBIDDEN)

        var deleted = 0
        var uploaderRemoved = 0
        found.forEach { photo ->
            if (photo.id !in ownPhotoIds) {
                if (photoService.deletePhoto(bunchId, photo.id)) deleted++
                return@forEach
            }
            val result = photoService.removeUploader(bunchId, photo.id, userId)
            if (result.photoDeleted) deleted++ else if (result.uploaderRemoved) uploaderRemoved++
        }
        return PhotoDeleteResponse(deleted, uploaderRemoved)
    }

    private fun fail(errorCode: ErrorCode): Nothing = throw BusinessException(errorCode)

    companion object {
        const val MAX_PHOTOS_PER_REQUEST = 50
    }
}
