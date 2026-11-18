package com.bunch.recommendation.service

import com.bunch.photo.domain.PhotoSourceType
import com.bunch.recommendation.domain.CombinationPhotoRow
import com.bunch.recommendation.domain.RecommendationRepository
import com.bunch.recommendation.domain.combination.AiStatus
import com.bunch.recommendation.domain.combination.RecoPhoto
import com.bunch.recommendation.domain.combination.SourceType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.ZoneOffset

/**
 * Makes the input of the recommended bundle combination from what the database holds of a bunch (recommended bundle
 * specification 3-1, 5-1, 5-2). The photos of a draft or a published bundle and the photos that are not ready are not
 * there (3-1). A photo without a date is there as a photo of which the time is unknown, because the steps after this
 * one may give it a date (5-3). The photos come in the order of their numbers, so the same bunch gives the same input
 * every time.
 *
 * What the AI and the quality calculation will tell is not known yet, so it stays empty: no quality, no analysis and
 * no embedding. The photo is given the status of a failed analysis (4-3): it has no clue, its activity is unknown, and
 * it does not hold the day back as a pending photo would (5-6).
 */
@Service
class RecoPhotoInputBuilder(
    private val recommendations: RecommendationRepository
) {
    @Transactional(readOnly = true)
    fun build(bunchId: Long): List<RecoPhoto> =
        recommendations.findCombinationRows(bunchId).groupBy { it.photoId }.values.map(::photoOf)

    private fun photoOf(rows: List<CombinationPhotoRow>): RecoPhoto {
        val row = rows.first()
        return RecoPhoto(
            photoId = row.photoId,
            capturedDate = row.capturedDate,
            capturedTime = row.capturedTime,
            utcOffset = row.utcOffset?.let(ZoneOffset::of),
            uploads = rows.map { RecoPhoto.Upload(memberId = it.memberId, uploadBatchId = it.uploadBatchId) },
            uploadedAt = row.createdAt,
            source = sourceOf(row),
            quality = null,
            aiStatus = AiStatus.FAILED,
            fingerprint = row.imageFingerprint,
            dateBatchId = row.dateBatchId
        )
    }

    // The original type the app told. A photo uploaded before the app told it is read by the rule of 4-4: a photo is a
    // camera photo when it has a shooting time, and a saved image when it has none (a screenshot or a photo from a
    // messenger has no date to read). This is the one place that decides it, until the planner answers.
    private fun sourceOf(row: CombinationPhotoRow): SourceType = when (row.source) {
        PhotoSourceType.CAMERA -> SourceType.CAMERA
        PhotoSourceType.SCREENSHOT -> SourceType.SCREENSHOT
        PhotoSourceType.SAVED -> SourceType.SAVED
        null -> if (row.capturedDate != null && row.capturedTime != null) SourceType.CAMERA else SourceType.SAVED
    }
}
