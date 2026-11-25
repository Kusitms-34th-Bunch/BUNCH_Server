package com.bunch.global.image

import java.util.concurrent.Semaphore

/**
 * Bounds the number of image decodes that may hold decoded pixel buffers at once.
 *
 * The permit is deliberately process-wide: uploads and profile-image processing must share the same
 * memory budget even when they are served by different Spring services.
 */
public object ImageDecodeLimiter {
    private val permits = Semaphore(2, true)

    @JvmStatic
    public fun <T> withPermit(action: () -> T): T {
        try {
            permits.acquire()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Image decode was interrupted while waiting for a permit", interrupted)
        }

        try {
            return action()
        } finally {
            permits.release()
        }
    }
}
