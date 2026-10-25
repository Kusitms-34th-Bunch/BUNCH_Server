package com.bunch.photo.service

fun interface PhotoFinalDeletionCallback {
    fun beforePhotoDelete(photoId: Long)

    companion object {
        val NONE = PhotoFinalDeletionCallback { }
    }
}
