package com.bunch.recommendation.combination

import com.bunch.recommendation.domain.combination.BundleVisibility
import com.bunch.recommendation.domain.combination.CombinationSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** From when a recommended bundle is shown (specification 10-4): the day after its day, at 05:00 on the local clock. */
class BundleVisibilityTest {
    private val visibility = BundleVisibility()
    private val day = LocalDate.parse("2026-10-19")

    // The end of the day of the 19th is 05:00 of the 20th in Seoul, which is 20:00 of the 19th in UTC.
    private val dayEnd = Instant.parse("2026-10-19T20:00:00Z")

    @Test
    fun `a day that is not over is shown when it is over, not when it is calculated`() {
        val calculated = Instant.parse("2026-10-19T12:00:00Z")

        assertThat(visibility.visibleFrom(calculated, day)).isEqualTo(dayEnd)
    }

    @Test
    fun `a bundle calculated after the day is over is shown when it is calculated`() {
        val calculated = Instant.parse("2026-10-21T03:00:00Z")

        assertThat(visibility.visibleFrom(calculated, day)).isEqualTo(calculated)
    }

    @Test
    fun `the end of the day is at 05 00 on the local clock, one minute before it is still before`() {
        val before = dayEnd.minusSeconds(60)
        val at = dayEnd

        assertThat(visibility.visibleFrom(before, day)).isEqualTo(dayEnd)
        assertThat(visibility.visibleFrom(at, day)).isEqualTo(dayEnd)
        assertThat(visibility.visibleFrom(at.plusSeconds(1), day)).isEqualTo(at.plusSeconds(1))
    }

    @Test
    fun `a day that was over long ago is shown at once`() {
        val calculated = Instant.parse("2026-11-19T00:00:00Z")

        assertThat(visibility.visibleFrom(calculated, day)).isEqualTo(calculated)
    }

    @Test
    fun `the end of the day follows the settings`() {
        val late = BundleVisibility(CombinationSettings(dayStartHour = 4, afternoonStartHour = 12))
        val abroad = BundleVisibility(CombinationSettings(defaultZone = ZoneId.of("UTC")))
        val calculated = Instant.parse("2026-10-19T00:00:00Z")

        assertThat(late.visibleFrom(calculated, day)).isEqualTo(Instant.parse("2026-10-19T19:00:00Z"))
        assertThat(abroad.visibleFrom(calculated, day)).isEqualTo(Instant.parse("2026-10-20T05:00:00Z"))
    }

    @Test
    fun `the day is the day of the bundle, so a bundle of the 31st of December is shown on the 1st of January`() {
        val calculated = Instant.parse("2026-12-31T05:00:00Z")

        assertThat(visibility.visibleFrom(calculated, LocalDate.parse("2026-12-31")))
            .isEqualTo(Instant.parse("2026-12-31T20:00:00Z"))
    }
}
