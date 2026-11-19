package com.bunch.recommendation.domain.combination

import java.time.Instant
import java.time.LocalDate

/**
 * From when a recommended bundle is shown (recommended bundle specification 10-4): the later of the time it is
 * calculated and the start of the day after its day, which is [CombinationSettings.dayStartHour] on the local clock.
 * The members of a bunch often upload the photos of a day over several days, so a day is recommended for the first time
 * when it is over, and before that it is only calculated. A day that is over long ago is shown at once.
 */
class BundleVisibility(
    private val settings: CombinationSettings = CombinationSettings()
) {
    fun visibleFrom(calculatedAt: Instant, dayKey: LocalDate): Instant {
        val dayEnd = dayKey.plusDays(1).atTime(settings.dayStartHour, 0).atZone(settings.defaultZone).toInstant()
        return maxOf(calculatedAt, dayEnd)
    }
}
