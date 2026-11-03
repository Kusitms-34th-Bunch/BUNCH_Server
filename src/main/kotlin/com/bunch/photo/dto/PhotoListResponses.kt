package com.bunch.photo.dto

import java.time.LocalDate
import java.time.LocalTime

data class PhotoListResponse(
    val totalCount: Long,
    val photos: List<PhotoListItem>,
    val nextCursor: String?
)

data class PhotoListItem(
    val photoId: Long,
    val thumbnailUrl: String,
    val capturedDate: LocalDate,
    val capturedTime: LocalTime?,
    val uploaderIds: List<Long>
)
