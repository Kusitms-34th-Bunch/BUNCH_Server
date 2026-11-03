package com.bunch.photo.dto

import com.bunch.photo.service.PhotoUploadCommand
import org.springframework.format.annotation.DateTimeFormat
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * The metadata that travels with the photo file in the multipart request.
 * The file itself stays a separate part.
 */
data class PhotoUploadRequest(
    val uploadBatchId: UUID,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    val capturedDate: LocalDate? = null,
    @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
    val capturedTime: LocalTime? = null
) {
    fun toCommand(bunchId: Long, memberId: Long) =
        PhotoUploadCommand(bunchId, memberId, uploadBatchId, capturedDate, capturedTime)
}
