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

enum class PhotoUploadKind {
    /** A new photo was stored. */
    STORED,

    /** The same file was already in the bunch; this member was added as an uploader. */
    MERGED,

    /** The same file was already uploaded by this member. Nothing changed. */
    ALREADY_UPLOADED
}

data class PhotoUploadResult(val photoId: Long, val kind: PhotoUploadKind)
