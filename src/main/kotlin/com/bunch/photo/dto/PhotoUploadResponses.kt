package com.bunch.photo.dto

import com.bunch.photo.service.PhotoUploadKind
import com.bunch.photo.service.PhotoUploadResult

data class PhotoUploadResponse(
    val photoId: Long,
    val result: PhotoUploadKind
) {
    companion object {
        fun from(result: PhotoUploadResult) = PhotoUploadResponse(result.photoId, result.kind)
    }
}
