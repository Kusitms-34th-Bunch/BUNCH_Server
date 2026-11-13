package com.bunch.photo.service

import com.bunch.photo.domain.PhotoSourceType
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * One photo upload. The batch ID is the one the client picked for the whole selection. [capturedUtcOffset] is already
 * checked (`±HH:mm`) and [sourceType] already named as the specification does; both are null when the app did not say.
 */
data class PhotoUploadCommand(
    val bunchId: Long,
    val memberId: Long,
    val uploadBatchId: UUID,
    val capturedDate: LocalDate? = null,
    val capturedTime: LocalTime? = null,
    val capturedUtcOffset: String? = null,
    val sourceType: PhotoSourceType? = null
)
