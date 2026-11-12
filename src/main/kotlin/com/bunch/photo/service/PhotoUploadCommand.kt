package com.bunch.photo.service

import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * One photo upload. The batch ID is the one the client picked for the whole selection. The original file hash is the
 * SHA-256 the app computed on the file before it shrank the photo; it is not checked against the file.
 */
data class PhotoUploadCommand(
    val bunchId: Long,
    val memberId: Long,
    val uploadBatchId: UUID,
    val capturedDate: LocalDate? = null,
    val capturedTime: LocalTime? = null,
    val originalFileHash: String? = null
)
