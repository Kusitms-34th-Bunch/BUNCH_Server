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
 * [embedding] is null when it is not made. [fingerprint] is the 64-bit difference hash of the image that the upload
 * stores (photo box 4.2.8 rule 1); it is null for a photo uploaded before it was stored or whose image could not be
 * read. A photo that has neither an embedding nor a fingerprint cannot be told alike to another one, so it is only
 * joined to the same picture (5-4).
 *
 * [capturedMillis] is the part of the shooting time below the second, [deviceModel] is the model of the device that
 * took the photo, and [width] and [height] are the size of the stored image (specification 2-1, F7 and R1). The app
 * leaves out what the device did not record, and an upload before they were stored has none. A value that is null is
 * not known: it is not 0 milliseconds and not an unknown device, and it never makes two photos alike.
 *
 * [dateBatchId] is the batch of the photos whose dates the user set in one request; only a photo the user dated has
 * one. Photos of the same batch of an upload ([Upload.uploadBatchId]) or of the same date batch belong together
 * (photo box 4.2.7 rule 3). Whether two photos belong together is asked by the steps that use it; this value only
 * carries the batch.
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
    val embedding: Embedding? = null,
    val fingerprint: Long? = null,
    val dateBatchId: UUID? = null,
    val capturedMillis: Int? = null,
    val deviceModel: String? = null,
    val width: Int? = null,
    val height: Int? = null
) {
    init {
        require(capturedTime == null || capturedDate != null) {
            "capturedTime requires capturedDate"
        }
        require(capturedMillis == null || (capturedMillis in 0..MAX_MILLIS && capturedTime != null)) {
            "milliseconds are from 0 to 999 and need a capturedTime"
        }
        require((width == null) == (height == null) && (width ?: 1) > 0 && (height ?: 1) > 0) {
            "the width and the height are given together and are above zero"
        }
        require(dateBatchId == null || capturedDate != null) {
            "a date batch requires capturedDate"
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

    /**
     * Whether the two photos are of one batch: they were uploaded together, or their dates were set together (photo box
     * 4.2.7 rule 3).
     */
    fun sharesBatchWith(other: RecoPhoto): Boolean =
        uploads.any { upload -> other.uploads.any { it.uploadBatchId == upload.uploadBatchId } } ||
            (dateBatchId != null && dateBatchId == other.dateBatchId)

    /**
     * One member who uploaded the photo, the batch of the upload (3.2.2 rule 3, 4.2.7 rule 3) and when this member
     * uploaded it. A photo several members uploaded has a time for each of them; of two members who share it, the one
     * with the earlier time uploaded it first (photo box 4.2.8 rule 3).
     */
    data class Upload(
        val memberId: Long,
        val uploadBatchId: UUID,
        val uploadedAt: Instant
    )

    private companion object {
        const val MAX_MILLIS = 999
    }
}
