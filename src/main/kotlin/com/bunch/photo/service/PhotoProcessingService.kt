package com.bunch.photo.service

import com.bunch.photo.domain.PhotoRepository
import com.bunch.photo.domain.ProcessingStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PhotoProcessingService(
    private val photos: PhotoRepository
) {
    /** Returns true only when this call moved the photo from PENDING to READY. */
    @Transactional
    fun markReady(photoId: Long): Boolean = transition(photoId, ProcessingStatus.READY)

    /** Returns true only when this call moved the photo from PENDING to FAILED. */
    @Transactional
    fun markFailed(photoId: Long): Boolean = transition(photoId, ProcessingStatus.FAILED)

    // READY and FAILED are final. A repeated call, or a photo deleted meanwhile, changes nothing.
    private fun transition(photoId: Long, to: ProcessingStatus): Boolean =
        photos.transitionStatus(photoId, ProcessingStatus.PENDING, to) == 1
}
