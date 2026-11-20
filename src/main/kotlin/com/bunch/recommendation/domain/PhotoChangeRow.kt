package com.bunch.recommendation.domain

import java.time.LocalDate

/** The bunch of a photo and its date, which tell what to calculate again when something about the photo changed. */
interface PhotoChangeRow {
    val bunchId: Long
    val capturedDate: LocalDate?
}
