package com.bunch.photo.dto

data class PhotoUndatedResponse(
    val totalCount: Long,
    val photos: List<PhotoUndatedItem>,
    val nextCursor: String?
)

data class PhotoUndatedItem(
    val photoId: Long,
    val thumbnailUrl: String,
    val uploaderIds: List<Long>
)
