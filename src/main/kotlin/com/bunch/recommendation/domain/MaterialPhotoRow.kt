package com.bunch.recommendation.domain

import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** One uploader of one material photo. A photo uploaded together by several members comes as several rows. */
interface MaterialPhotoRow {
    val photoId: Long
    val capturedDate: LocalDate
    val capturedTime: LocalTime?
    val memberId: Long
    val uploadBatchId: UUID

    /** The batch of the photos that were given a date in one request, or null (4.2.7 rule 3). */
    val dateBatchId: UUID?

    /** The image fingerprint of the photo, or null when it has none (4.2.8 rule 1). */
    val imageFingerprint: Long?
}
