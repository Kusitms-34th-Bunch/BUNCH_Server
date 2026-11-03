package com.bunch.photo.service

data class PhotoUploaderRemovalResult(
    val photoId: Long,
    val uploaderRemoved: Boolean,
    val photoDeleted: Boolean
)
