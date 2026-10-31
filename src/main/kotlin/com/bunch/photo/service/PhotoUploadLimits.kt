package com.bunch.photo.service

import java.time.Instant
import java.time.ZoneId

object PhotoUploadLimits {
    const val MAX_PHOTOS_PER_BATCH = 50

    // Counted per member and per bunch: a member who reached the limit in one bunch can still upload to another.
    const val MAX_PHOTOS_PER_MEMBER_PER_BUNCH_PER_DAY = 100
    const val MAX_PHOTOS_PER_BUNCH = 1_000

    // The daily limit follows the Korean calendar day, not the server or database time zone.
    private val UPLOAD_DAY_ZONE: ZoneId = ZoneId.of("Asia/Seoul")

    fun startOfUploadDay(now: Instant): Instant =
        now.atZone(UPLOAD_DAY_ZONE).toLocalDate().atStartOfDay(UPLOAD_DAY_ZONE).toInstant()
}
