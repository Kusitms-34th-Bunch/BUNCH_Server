package com.bunch.photo.service

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object PhotoUploadLimits {
    const val MAX_PHOTOS_PER_BATCH = 50

    // Counted per member and per bunch: a member who reached the limit in one bunch can still upload to another.
    const val MAX_PHOTOS_PER_MEMBER_PER_BUNCH_PER_DAY = 100
    const val MAX_PHOTOS_PER_BUNCH = 1_000

    // How many of the 64 bits of two image fingerprints may differ when the app says both files come from one
    // original. Two renderings of one original differ in almost no bit, while a wrong hash joins pictures that are
    // unrelated and differ in about half of them. A first value, to be tuned from the warning logs.
    const val MAX_ORIGINAL_HASH_FINGERPRINT_DIFFERENCE = 8

    // The daily limit follows the Korean calendar day, not the server or database time zone.
    private val UPLOAD_DAY_ZONE: ZoneId = ZoneId.of("Asia/Seoul")

    fun startOfUploadDay(now: Instant): Instant =
        now.atZone(UPLOAD_DAY_ZONE).toLocalDate().atStartOfDay(UPLOAD_DAY_ZONE).toInstant()

    /** Today on the Korean calendar, the day after which no capture date can be chosen (3.4.1). */
    fun today(now: Instant): LocalDate = now.atZone(UPLOAD_DAY_ZONE).toLocalDate()
}
