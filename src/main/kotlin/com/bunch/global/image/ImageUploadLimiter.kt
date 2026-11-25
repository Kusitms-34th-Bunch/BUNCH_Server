package com.bunch.global.image

import java.util.concurrent.Semaphore

/** Bound retained upload bytes as well as the raster while waiting for object storage. */
object ImageUploadLimiter {
    private val permits = Semaphore(2, true)

    fun <T> withPermit(action: () -> T): T {
        try {
            permits.acquire()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Image upload interrupted", interrupted)
        }
        return try {
            action()
        } finally {
            permits.release()
        }
    }
}
