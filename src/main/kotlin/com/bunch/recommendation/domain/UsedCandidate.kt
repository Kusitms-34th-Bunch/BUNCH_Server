package com.bunch.recommendation.domain

import java.time.LocalDate

/**
 * What a draft takes over from the recommended bundle it was made from (4.1.3, 4.3.11): the title the draft starts
 * with and the first day of the recommended bundle, which is the first date chip of the draft. The recommended bundle
 * itself is deleted when it is used, so the draft keeps these.
 */
data class UsedCandidate(val title: String, val startDate: LocalDate)
