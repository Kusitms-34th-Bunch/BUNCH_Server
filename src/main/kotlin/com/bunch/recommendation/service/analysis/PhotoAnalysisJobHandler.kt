package com.bunch.recommendation.service.analysis

import com.bunch.global.job.AsyncJobHandler
import org.springframework.stereotype.Component

/** Runs the job of the AI analysis of a photo. The target of the job is the number of the photo. */
@Component
class PhotoAnalysisJobHandler(
    private val analysis: PhotoAnalysisService
) : AsyncJobHandler {
    override val jobType: String = PhotoAnalysisService.JOB_TYPE

    override fun handle(targetKey: String) {
        // A key that is no number names no photo, and asking again does not make it one.
        val photoId = targetKey.toLongOrNull() ?: return
        analysis.analyze(photoId)
    }
}
