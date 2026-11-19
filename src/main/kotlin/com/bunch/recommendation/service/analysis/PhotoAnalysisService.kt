package com.bunch.recommendation.service.analysis

import com.bunch.global.job.AsyncJobService
import com.bunch.photo.domain.PhotoAiStatus
import com.bunch.photo.domain.PhotoRecoFeatureRepository
import com.bunch.recommendation.domain.RecommendationRepository
import com.bunch.recommendation.domain.analysis.PhotoAnalysisFailure
import com.bunch.recommendation.domain.analysis.PhotoAnalyzer
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * Asks for the AI analysis of a photo and makes it (recommended bundle specification 4-2, 4-3).
 *
 * [request] marks the photo as waiting and puts a job in the queue, in the transaction of the caller, so the job exists
 * exactly when the caller's change is saved. The job calls [analyze], which does the work. Nothing calls [request] yet:
 * a photo must not be sent to an AI service before the member has agreed to it, and the agreement comes with its own
 * issue. Until then no photo of a member leaves the server.
 */
@Service
class PhotoAnalysisService(
    private val features: PhotoRecoFeatureRepository,
    private val recommendations: RecommendationRepository,
    private val jobs: AsyncJobService,
    private val images: PhotoAnalysisImageLoader,
    private val analyzer: PhotoAnalyzer,
    private val clock: Clock
) {
    /** Returns false when the photo is already analyzed or already waiting in the queue. */
    @Transactional
    fun request(photoId: Long): Boolean {
        if (features.markPending(photoId) == 0) return false
        return jobs.enqueue(JOB_TYPE, photoId.toString())
    }

    /**
     * Makes the analysis of the photo and stores it. It may run more than once for the same photo (the queue can run a
     * job again), so a photo that is done is left as it is. No transaction is held while the model is asked, because
     * that takes seconds.
     *
     * A failure that asking again cannot mend makes the photo a failed one (4-3) and the job ends. A failure that a
     * later request can mend is thrown, so that the queue asks again after its waiting time. If the queue gives up, the
     * photo stays waiting until the waiting time of the specification (`AI_PENDING_TIMEOUT`) is over, and is read as a
     * failed one from then on.
     */
    fun analyze(photoId: Long) {
        val url = recommendations.findImageUrl(photoId) ?: return
        if (features.findById(photoId).orElse(null)?.aiStatus == PhotoAiStatus.DONE) return

        try {
            val result = analyzer.analyze(images.load(url))
            features.markDone(photoId, result.modelVersion, result.raw, clock.instant())
        } catch (failure: PhotoAnalysisFailure) {
            if (failure.reason.retryable) throw failure
            features.markFailed(photoId, clock.instant())
        }
    }

    companion object {
        const val JOB_TYPE = "PHOTO_AI_ANALYSIS"
    }
}
