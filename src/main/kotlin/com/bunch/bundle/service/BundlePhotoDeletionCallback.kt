package com.bunch.bundle.service

import com.bunch.photo.service.PhotoFinalDeletionCallback
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
class BundlePhotoDeletionCallback(private val bundles: BundleService) : PhotoFinalDeletionCallback {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun beforePhotoDelete(photoId: Long) {
        bundles.beforePhotoDelete(photoId)
    }
}
