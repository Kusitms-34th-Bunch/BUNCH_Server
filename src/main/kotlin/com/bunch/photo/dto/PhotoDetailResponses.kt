package com.bunch.photo.dto

import java.time.LocalDate

data class PhotoDetailResponse(
    val photoId: Long,
    val imageUrl: String,
    val capturedDate: LocalDate?,
    val uploaders: List<PhotoDetailUploader>
)

data class PhotoDetailUploader(
    val memberId: Long,
    val nickname: String?,
    val imageUrl: String?
)
