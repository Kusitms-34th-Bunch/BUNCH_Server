package com.bunch.recommendation.domain

import java.time.LocalDate

/** A day of a bunch that waits to be calculated again (recommended bundle specification 10-2). */
interface QueuedDayRow {
    val dayKey: LocalDate
}
