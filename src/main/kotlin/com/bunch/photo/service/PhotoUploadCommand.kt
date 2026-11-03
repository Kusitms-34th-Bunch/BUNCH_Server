package com.bunch.photo.service

import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** One photo upload. The batch ID is the one the client picked for the whole selection. */
data class PhotoUploadCommand(
    val bunchId: Long,
    val memberId: Long,
    val uploadBatchId: UUID,
    val capturedDate: LocalDate? = null,
    val capturedTime: LocalTime? = null
)
