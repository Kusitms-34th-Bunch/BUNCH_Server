package com.bunch.recommendation.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

/**
 * One photo as the grouping sees it. A photo several members uploaded together is still one sample with all of them
 * in [uploaderIds]. [batchIds] are the batches the photo belongs to: the batches it was uploaded in and the batch of
 * the photos that were given a date in one request (4.2.7 rule 3). [capturedDate] is the date the photo box shows, so
 * a date the user set is already in it.
 * [capturedTime] is the shooting time only: an upload time never stands in for it. [fingerprint] is a 64-bit value of
 * the image that is close for photos that look alike. Where it comes from is not decided here, so it may be missing.
 */
data class PhotoSample(
    val photoId: Long,
    val capturedDate: LocalDate,
    val capturedTime: LocalTime?,
    val uploaderIds: Set<Long>,
    val batchIds: Set<UUID> = emptySet(),
    val fingerprint: Long? = null
) {
    val moment: LocalDateTime? get() = capturedTime?.let { LocalDateTime.of(capturedDate, it) }
}
