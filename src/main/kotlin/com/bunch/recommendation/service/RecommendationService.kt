package com.bunch.recommendation.service

import com.bunch.recommendation.domain.PhotoSample
import com.bunch.recommendation.domain.RecommendationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class RecommendationService(
    private val recommendations: RecommendationRepository
) {
    /** The photos of a bunch a recommended bundle can be made of, one sample per photo. */
    @Transactional(readOnly = true)
    fun materialPhotos(bunchId: Long): List<PhotoSample> =
        recommendations.findMaterialRows(bunchId)
            .groupBy { it.photoId }
            .map { (photoId, uploads) ->
                PhotoSample(
                    photoId = photoId,
                    capturedDate = uploads.first().capturedDate,
                    capturedTime = uploads.first().capturedTime,
                    uploaderIds = uploads.map { it.memberId }.toSet(),
                    batchIds = uploads.map { it.uploadBatchId }.toSet()
                )
            }
}
