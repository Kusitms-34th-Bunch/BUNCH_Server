package com.bunch.recommendation.service

import com.bunch.global.image.ImageQualityGrade
import com.bunch.photo.domain.PhotoAiStatus
import com.bunch.photo.domain.PhotoRecoFeatureRepository
import com.bunch.photo.domain.PhotoSourceType
import com.bunch.recommendation.domain.CombinationPhotoRow
import com.bunch.recommendation.domain.RecommendationRepository
import com.bunch.recommendation.domain.analysis.AiAnswerParser
import com.bunch.recommendation.domain.analysis.PhotoAnalysisFailure
import com.bunch.recommendation.domain.combination.AiAnalysis
import com.bunch.recommendation.domain.combination.AiStatus
import com.bunch.recommendation.domain.combination.CombinationSettings
import com.bunch.recommendation.domain.combination.PhotoQuality
import com.bunch.recommendation.domain.combination.RecoPhoto
import com.bunch.recommendation.domain.combination.SourceType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZoneOffset

/**
 * Makes the input of the recommended bundle combination from what the database holds of a bunch (recommended bundle
 * specification 3-1, 5-1, 5-2). The photos of a draft or a published bundle and the photos that are not ready are not
 * there (3-1). A photo without a date is there as a photo of which the time is unknown: the preparation of the day
 * leaves it out (X2). The photos come in the order of their numbers, so the same bunch gives the same input every
 * time.
 *
 * The quality is the one calculated when the photo was uploaded; a photo without it has none (it is not low, 3-1). What
 * the AI told is the stored answer of the analysis (4-3), read again by the same class that read it when it came. The
 * embedding stays empty: nothing makes it yet. A photo that was never sent to the analysis is given the status of a
 * failed one: it has no clue, its activity is unknown, and it does not hold the day back as a pending photo would
 * (5-6). A photo that has waited longer than the waiting time of the specification is read as a failed one too.
 */
@Service
class RecoPhotoInputBuilder(
    private val recommendations: RecommendationRepository,
    private val features: PhotoRecoFeatureRepository,
    private val settings: CombinationSettings,
    private val clock: Clock
) {
    private val parser = AiAnswerParser()

    @Transactional(readOnly = true)
    fun build(bunchId: Long): List<RecoPhoto> {
        val photos = recommendations.findCombinationRows(bunchId).groupBy { it.photoId }.values
        val answers = answersOf(photos)
        return photos.map { photoOf(it, answers[it.first().photoId]) }
    }

    // The stored answers of the photos whose analysis is done, read once for all of them.
    private fun answersOf(photos: Collection<List<CombinationPhotoRow>>): Map<Long, String> {
        val done = photos.map { it.first() }.filter { it.aiStatus == PhotoAiStatus.DONE }.map { it.photoId }
        if (done.isEmpty()) return emptyMap()
        return features.findAnswers(done).associate { it.photoId to it.raw }
    }

    private fun photoOf(rows: List<CombinationPhotoRow>, answer: String?): RecoPhoto {
        val row = rows.first()
        val analysis = analysisOf(row, answer)
        return RecoPhoto(
            photoId = row.photoId,
            capturedDate = row.capturedDate,
            capturedTime = row.capturedTime,
            utcOffset = row.utcOffset?.let(ZoneOffset::of),
            uploads = rows.map {
                RecoPhoto.Upload(
                    memberId = it.memberId,
                    uploadBatchId = it.uploadBatchId,
                    uploadedAt = it.memberUploadedAt
                )
            },
            uploadedAt = row.createdAt,
            source = sourceOf(row),
            quality = row.quality?.let(::qualityOf),
            aiStatus = analysis.status,
            analysis = analysis.analysis,
            fingerprint = row.imageFingerprint,
            dateBatchId = row.dateBatchId,
            capturedMillis = row.capturedMillis,
            deviceModel = row.deviceModel,
            width = row.width,
            height = row.height
        )
    }

    private data class Analysis(val status: AiStatus, val analysis: AiAnalysis)

    // What the AI told of the photo (4-3). A photo never sent to the analysis is a failed one. A photo that has waited
    // longer than the waiting time of the specification is a failed one too, whatever the queue is still doing. A photo
    // that is done and has a stored answer that cannot be read is a failed one: its answer cannot be trusted. One that
    // is done and has no stored answer is read as done with nothing in it, as it was before the answer was stored.
    private fun analysisOf(row: CombinationPhotoRow, answer: String?): Analysis {
        val failed = Analysis(AiStatus.FAILED, AiAnalysis.NONE)
        return when (row.aiStatus) {
            null,
            PhotoAiStatus.FAILED -> failed
            PhotoAiStatus.PENDING ->
                if (row.createdAt.plus(settings.aiPendingTimeout) <= clock.instant()) {
                    failed
                } else {
                    Analysis(AiStatus.PENDING, AiAnalysis.NONE)
                }
            PhotoAiStatus.DONE ->
                if (answer == null) Analysis(AiStatus.DONE, AiAnalysis.NONE) else parsed(answer, failed)
        }
    }

    private fun parsed(answer: String, failed: Analysis): Analysis = try {
        Analysis(AiStatus.DONE, parser.parse(answer))
    } catch (_: PhotoAnalysisFailure) {
        failed
    }

    private fun qualityOf(grade: ImageQualityGrade): PhotoQuality = when (grade) {
        ImageQualityGrade.GOOD -> PhotoQuality.GOOD
        ImageQualityGrade.NORMAL -> PhotoQuality.NORMAL
        ImageQualityGrade.LOW -> PhotoQuality.LOW
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
