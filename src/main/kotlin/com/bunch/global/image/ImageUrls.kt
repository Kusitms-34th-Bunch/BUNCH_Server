package com.bunch.global.image

import org.springframework.stereotype.Service

/** Call from the use case after its access checks, including when replaying an idempotent result. */
@Service
class ImageUrls(private val objects: R2ObjectStorage) {
    fun full(key: String): String = objects.sign(key)

    fun thumbnail(key: String): String = objects.sign(ImageObjectKeys.thumbnail(key))

    fun profile(key: String?): String? = key?.let(objects::sign)
}
