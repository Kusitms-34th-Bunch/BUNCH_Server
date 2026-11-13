package com.bunch.photo.dto

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.photo.domain.PhotoSourceType
import com.bunch.photo.domain.UtcOffset
import com.bunch.photo.service.PhotoUploadCommand
import org.springframework.format.annotation.DateTimeFormat
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * The metadata that travels with the photo file in the multipart request.
 * The file itself stays a separate part.
 *
 * [capturedUtcOffset] (`±HH:mm`) and [sourceType] are read as text and checked in [toCommand], so a wrong value is a
 * plain 400 that does not tell the reason (3.2.2 detail 4). An empty value counts as not sent, like an empty date.
 */
data class PhotoUploadRequest(
    val uploadBatchId: UUID,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    val capturedDate: LocalDate? = null,
    @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
    val capturedTime: LocalTime? = null,
    val capturedUtcOffset: String? = null,
    val sourceType: String? = null
) {
    fun toCommand(bunchId: Long, memberId: Long) = PhotoUploadCommand(
        bunchId = bunchId,
        memberId = memberId,
        uploadBatchId = uploadBatchId,
        capturedDate = capturedDate,
        capturedTime = capturedTime,
        capturedUtcOffset = checkedUtcOffset(),
        sourceType = checkedSourceType()
    )

    private fun checkedUtcOffset(): String? {
        val offset = capturedUtcOffset?.takeIf { it.isNotEmpty() } ?: return null
        if (!UtcOffset.isValid(offset)) throw BusinessException(ErrorCode.INVALID_INPUT)
        return offset
    }

    // The app names the saved image SAVED_IMAGE; the specification calls it SAVED. UNKNOWN is not kept.
    private fun checkedSourceType(): PhotoSourceType? = when (sourceType?.takeIf { it.isNotEmpty() }) {
        null, "UNKNOWN" -> null
        "CAMERA" -> PhotoSourceType.CAMERA
        "SCREENSHOT" -> PhotoSourceType.SCREENSHOT
        "SAVED_IMAGE" -> PhotoSourceType.SAVED
        else -> throw BusinessException(ErrorCode.INVALID_INPUT)
    }
}
