package com.bunch.photo.domain

/** One row of the list of photos without a date: the columns the list shows, without loading the photo. */
interface PhotoUndatedRow {
    val id: Long
    val imageUrl: String
}
