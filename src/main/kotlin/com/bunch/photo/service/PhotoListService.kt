package com.bunch.photo.service

import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bunch.domain.BunchRepository
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.image.PhotoImageUrls
import com.bunch.member.domain.MemberRepository
import com.bunch.photo.domain.PhotoListCursor
import com.bunch.photo.domain.PhotoRepository
import com.bunch.photo.domain.PhotoUploaderRepository
import com.bunch.photo.dto.PhotoListItem
import com.bunch.photo.dto.PhotoListResponse
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PhotoListService(
    private val members: MemberRepository,
    private val bunches: BunchRepository,
    private val memberships: BunchMembershipRepository,
    private val photos: PhotoRepository,
    private val photoUploaders: PhotoUploaderRepository
) {
    // Reads only. The list is not kept in step with other members' uploads; the app asks again (3.1.1).
    @Transactional(readOnly = true)
    fun list(userId: Long, bunchId: Long, uploaderId: Long?, cursor: String?, size: Int): PhotoListResponse {
        requireMember(userId, bunchId)
        if (size !in 1..MAX_SIZE) fail(ErrorCode.INVALID_INPUT)
        val after = cursor?.let(PhotoListCursor::decode)
        // A filter on someone who is not a member could never be picked from the chips.
        if (uploaderId != null && !isMember(uploaderId, bunchId)) fail(ErrorCode.INVALID_INPUT)

        // One extra row tells whether another page follows.
        val position = after ?: PhotoListCursor.FIRST
        val limit = Limit.of(size + 1)
        val rows = if (uploaderId == null) {
            photos.findListPage(bunchId, position, limit)
        } else {
            photos.findListPageByUploader(bunchId, uploaderId, position, limit)
        }
        val page = rows.take(size)
        val uploaders = if (page.isEmpty()) {
            emptyMap()
        } else {
            photoUploaders.findUploaderIdsByPhotoIdIn(page.map { it.id }).groupBy({ it.photoId }, { it.memberId })
        }
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
                    uploaderIds = uploaders[it.id].orEmpty()
                )
            },
            nextCursor = nextCursor
        )
    }

    private fun requireMember(userId: Long, bunchId: Long) {
        members.findById(userId).orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }.requireCompleted()
        if (!bunches.existsById(bunchId)) fail(ErrorCode.RESOURCE_NOT_FOUND)
        if (!isMember(userId, bunchId)) fail(ErrorCode.FORBIDDEN)
    }

    private fun isMember(memberId: Long, bunchId: Long) =
        memberships.findByMemberIdAndBunchIdAndStatus(memberId, bunchId) != null

    private fun fail(errorCode: ErrorCode): Nothing = throw BusinessException(errorCode)

    companion object {
        const val DEFAULT_SIZE = 60
        const val MAX_SIZE = 100
    }
}
