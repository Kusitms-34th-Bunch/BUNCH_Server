package com.bunch.recommendation.domain

import com.bunch.global.image.ImageQualityGrade
import com.bunch.photo.domain.PhotoAiStatus
import com.bunch.photo.domain.PhotoSourceType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * One uploader of one photo of the bunch, with what the combination reads of the photo (recommended bundle
 * specification 4-1). A photo uploaded together by several members comes as several rows. A photo without a date is a
 * row too, because the steps after this one may give it a date (5-3).
 */
interface CombinationPhotoRow {
    val photoId: Long
    val capturedDate: LocalDate?
    val capturedTime: LocalTime?

    /** The UTC offset of the place where the photo was taken, as stored (+09:00), or null. */
    val utcOffset: String?

    /** When the photo was first stored. */
    val createdAt: Instant
    val memberId: Long

    /** When this member uploaded the photo (not when the photo was first stored, which is [createdAt]). */
    val memberUploadedAt: Instant
    val uploadBatchId: UUID

    /** The batch of the photos that were given a date in one request, or null (4.2.7 rule 3). */
    val dateBatchId: UUID?

    /** The image fingerprint of the photo, or null when it has none (4.2.8 rule 1). */
    val imageFingerprint: Long?

    /** The part of the shooting time below the second (0 to 999), or null when the device did not record it. */
    val capturedMillis: Int?

    /** The model of the device that took the photo, or null when the app did not tell it. */
    val deviceModel: String?

    /** The size of the stored image in pixels, or null for a photo stored before they were kept. */
    val width: Int?
    val height: Int?

    /** The original type the app told when the photo was uploaded, or null when it did not tell. */
    val source: PhotoSourceType?

    /** The quality calculated when the photo was uploaded, or null when it was not (4-4). */
    val quality: ImageQualityGrade?

    /** How far the AI analysis is, or null when it was never asked for (4-3). */
    val aiStatus: PhotoAiStatus?
}
