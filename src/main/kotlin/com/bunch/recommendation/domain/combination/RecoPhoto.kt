package com.bunch.recommendation.domain.combination

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * One photo as the combination sees it (recommended bundle specification 4-1). A photo that several members uploaded
 * together is one [RecoPhoto] with all of them in [uploads] (3-1: the uploaders are all counted).
 *
 * [capturedDate] and [capturedTime] are the shooting date and time on the local clock of the place where the photo was
 * taken; a date the user set is already in the date and the original time is kept (3.4.1). An upload time never stands
 * in for them. [utcOffset] is the offset of that place; without it the local clock is read as the default zone (5-2).
 * [uploadedAt] is when the photo was first stored: of two copies of one picture, the one stored first counts as
 * uploaded first (8-5), and a photo without a time is put last in the order it was uploaded (8-6).
 *
 * [quality] is null while it is not calculated. [analysis] is [AiAnalysis.NONE] while [aiStatus] is not done, and
 * [embedding] is null when it is not made (5-4: such a photo is only joined to the same picture).
 */
data class RecoPhoto(
    val photoId: Long,
    val capturedDate: LocalDate?,
    val capturedTime: LocalTime?,
    val utcOffset: ZoneOffset?,
    val uploads: List<Upload>,
    val uploadedAt: Instant,
    val source: SourceType,
    val quality: PhotoQuality?,
    val aiStatus: AiStatus,
    val analysis: AiAnalysis = AiAnalysis.NONE,
    val embedding: Embedding? = null
) {
    init {
        require(capturedTime == null || capturedDate != null) {
            "capturedTime requires capturedDate"
        }
        require(uploads.isNotEmpty()) {
            "a photo has at least one upload"
        }
    }

    /** How much of the shooting time is known, from the date and the time. */
    val timeState: TimeState
        get() = when {
            capturedDate == null -> TimeState.UNKNOWN
            capturedTime == null -> TimeState.DATE_ONLY
            else -> TimeState.FULL
        }

    /** One member who uploaded the photo and the batch of the upload (3.2.2 rule 3, 4.2.7 rule 3). */
    data class Upload(
        val memberId: Long,
        val uploadBatchId: UUID
    )
}
