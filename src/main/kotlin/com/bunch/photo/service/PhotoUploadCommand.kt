package com.bunch.photo.service

import com.bunch.photo.domain.PhotoSourceType
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * One photo upload. The batch ID is the one the client picked for the whole selection. The original file hash is the
 * SHA-256 the app computed on the file before it shrank the photo; it is not checked against the file.
 * [capturedUtcOffset] is already checked (`±HH:mm`) and [sourceType] already named as the specification does; both
 * are null when the app did not say. The same holds for [capturedMillis] (0 to 999, the part of the capture time
 * below the second) and [deviceModel] (the model of the camera, trimmed): null means the app did not say, never "0"
 * or an unknown device.
 */
data class PhotoUploadCommand(
    val bunchId: Long,
    val memberId: Long,
    val uploadBatchId: UUID,
    val capturedDate: LocalDate? = null,
    val capturedTime: LocalTime? = null,
    val originalFileHash: String? = null,
    val capturedUtcOffset: String? = null,
    val sourceType: PhotoSourceType? = null,
    val capturedMillis: Int? = null,
    val deviceModel: String? = null
)
