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
    // The SHA-256 of the original file in lowercase hexadecimal, computed by the app before it shrinks the photo.
    val originalFileHash: String? = null,
    val capturedUtcOffset: String? = null,
    val sourceType: String? = null,
    // The milliseconds of the capture time (0 to 999) and the model of the camera. The app leaves them out when the
    // device did not record them; a default such as 0 or "unknown" would be taken for a real value.
    val capturedMillis: String? = null,
    val deviceModel: String? = null
) {
    fun toCommand(bunchId: Long, memberId: Long) = PhotoUploadCommand(
        bunchId = bunchId,
        memberId = memberId,
        uploadBatchId = uploadBatchId,
        capturedDate = capturedDate,
        capturedTime = capturedTime,
        originalFileHash = originalFileHash,
        capturedUtcOffset = checkedUtcOffset(),
        sourceType = checkedSourceType(),
        capturedMillis = checkedMillis(),
        deviceModel = checkedDeviceModel()
    )

    // Exactly three digits, so "007" and "123" are accepted and "7", "-1", "1000" and "1.5" are not. A shorter text
    // could be the whole number of milliseconds ("7") or the fraction of a second ("7" is .7), and the server cannot
    // tell which.
    private fun checkedMillis(): Int? {
        val text = capturedMillis?.takeIf { it.isNotEmpty() } ?: return null
        if (!MILLIS.matches(text)) throw BusinessException(ErrorCode.INVALID_INPUT)
        return text.toInt()
    }

    // The model is only a hint for the recommendation, so a strange one never fails the upload of the photo: it is
    // dropped and the photo goes on without it. The blanks and the control characters at the ends (the text of an
    // EXIF tag often ends in a NUL) do not belong to the model and are cut. What is left is no model when it is empty,
    // too long, or still has a control character inside.
    private fun checkedDeviceModel(): String? {
        val model = deviceModel?.trim { it.isWhitespace() || it.isISOControl() }?.takeIf { it.isNotEmpty() }
            ?: return null
        if (model.length > MAX_DEVICE_MODEL_LENGTH || model.any { it.isISOControl() }) return null
        return model
    }

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

private val MILLIS = Regex("[0-9]{3}")

private const val MAX_DEVICE_MODEL_LENGTH = 64
