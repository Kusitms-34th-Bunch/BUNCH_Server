package com.bunch.photo.dto

import java.time.LocalDate

/**
 * Exactly one of [capturedDate] and [clearDate] is given: a date for all the photos, or `clearDate` to take the
 * date back to "no date" (the undo of dragging a photo without a date, 3.4.2).
 */
data class PhotoDateChangeRequest(
    val photoIds: List<Long>,
    val capturedDate: LocalDate? = null,
    val clearDate: Boolean = false
)

/** [updatedCount] photos changed; the toast is the same for one photo or many. */
data class PhotoDateChangeResponse(val updatedCount: Int)
