package com.bunch.recommendation.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

/**
 * One photo as the grouping sees it. A photo several members uploaded together is still one sample with all of them
 * in [uploaderIds]. [capturedDate] is the date the photo box shows, so a date the user set is already in it.
 * [capturedTime] is the shooting time only: an upload time never stands in for it.
 */
data class PhotoSample(
    val photoId: Long,
    val capturedDate: LocalDate,
    val capturedTime: LocalTime?,
    val uploaderIds: Set<Long>,
    val batchIds: Set<UUID> = emptySet()
) {
    val moment: LocalDateTime? get() = capturedTime?.let { LocalDateTime.of(capturedDate, it) }
}
