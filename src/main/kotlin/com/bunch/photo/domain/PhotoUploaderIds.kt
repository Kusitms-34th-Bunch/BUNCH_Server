package com.bunch.photo.domain

/** The photo and the uploading member of one uploader row, without loading either entity. */
interface PhotoUploaderIds {
    val photoId: Long
    val memberId: Long
}
