package com.bunch.recommendation.domain.combination

import java.time.Duration
import java.util.UUID

/**
 * Finds the photos of a device whose clock is wrong (recommended bundle specification, exception X5). A photo cannot
 * have been taken after it was uploaded, so a shooting time that is later than the time the photo was uploaded
 * ([RecoPhoto.Upload.uploadedAt], F4) means that the clock of the device is wrong. Such a clock is not corrected: the
 * whole upload batch ([RecoPhoto.Upload.uploadBatchId]) is left out of the calculation, because the other photos of
 * the batch were taken with the same clock.
 *
 * A photo without an offset is read in the default zone, and abroad (for example Australia, ahead of Korea) it can look
 * later than its upload although the clock is right. The biggest difference between two time zones, [margin] (14
 * hours), is allowed for such a photo. A photo with an offset has no such doubt, so it is compared without any margin.
 *
 * A photo that several members uploaded is judged by each upload. It is left out only when every batch that holds it
 * is wrong: if one member uploaded it with a clock that is right, the photo stays. The photos are only found here; the
 * photos themselves are not changed.
 */
class ClockErrorFilter(
    private val times: TimeNormalizer,
    private val margin: Duration
) {
    /** The IDs of the photos that are left out because of a wrong clock. */
    fun find(photos: List<TimedPhoto>): Set<Long> {
        val wrong = wrongBatches(photos)
        if (wrong.isEmpty()) return emptySet()
        return photos
            .filter { photo -> photo.photo.uploads.all { it.uploadBatchId in wrong } }
            .map { it.photoId }
            .toSet()
    }

    // The batches that hold a photo taken later than it was uploaded.
    private fun wrongBatches(photos: List<TimedPhoto>): Set<UUID> {
        val wrong = mutableSetOf<UUID>()
        for (photo in photos) {
            val takenAt = times.instant(photo) ?: continue
            val allowed = if (photo.offset == null) margin else Duration.ZERO
            for (upload in photo.photo.uploads) {
                if (takenAt.isAfter(upload.uploadedAt.plus(allowed))) wrong += upload.uploadBatchId
            }
        }
        return wrong
    }
}
