package com.bunch.photo.domain

import java.time.LocalDate

/** The columns of the photo detail: the stored image and the capture date, which is null when it is unknown. */
interface PhotoDetailRow {
    val id: Long
    val capturedDate: LocalDate?
    val imageUrl: String
}
