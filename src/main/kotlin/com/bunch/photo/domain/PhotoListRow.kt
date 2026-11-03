package com.bunch.photo.domain

import java.time.LocalDate
import java.time.LocalTime

/** One row of the photo box list: the columns the list shows, without loading the photo or its image asset. */
interface PhotoListRow {
    val id: Long
    val capturedDate: LocalDate
    val capturedTime: LocalTime?
    val imageUrl: String
}
