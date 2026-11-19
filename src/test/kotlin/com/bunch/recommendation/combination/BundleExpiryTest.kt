package com.bunch.recommendation.combination

import com.bunch.recommendation.domain.combination.BundleExpiry
import com.bunch.recommendation.domain.combination.CombinationSettings
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** When a recommended bundle is no longer shown: 30 days after the day its last photo was added (4.2.9 rule 4). */
class BundleExpiryTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val expiry = BundleExpiry()

    private fun at(dateTime: String): Instant = LocalDateTime.parse(dateTime).atZone(seoul).toInstant()

    @Test
    fun `a bundle whose last photo was added on October 1 is shown through October 31 and gone from November 1`() {
        val added = at("2026-10-01T12:00:00")

        assertThat(expiry.isExpired(added, at("2026-10-31T23:59:59"))).isFalse()
        assertThat(expiry.isExpired(added, at("2026-11-01T00:00:00"))).isTrue()
    }

    @Test
    fun `the days are counted as days, not as hours from the time the photo was added`() {
        // Added at the very end of October 1: the whole of October 31 is still within the 30 days.
        val late = at("2026-10-01T23:59:59")
        val early = at("2026-10-01T00:00:00")

        assertThat(expiry.isExpired(late, at("2026-10-31T23:59:59"))).isFalse()
        assertThat(expiry.isExpired(early, at("2026-10-31T23:59:59"))).isFalse()
        assertThat(expiry.isExpired(late, at("2026-11-01T00:00:00"))).isTrue()
        assertThat(expiry.isExpired(early, at("2026-11-01T00:00:00"))).isTrue()
    }

    @Test
    fun `a photo added today or a month ago has not expired`() {
        val now = at("2026-11-19T10:00:00")

        assertThat(expiry.isExpired(now, now)).isFalse()
        assertThat(expiry.isExpired(at("2026-10-20T00:00:00"), now)).isFalse()
        assertThat(expiry.isExpired(at("2026-10-19T23:59:59"), now)).isTrue()
    }

    @Test
    fun `the cutoff is the start of the day 30 days before today in the zone of the settings`() {
        // 16:00 UTC on the 18th is already the 19th in Seoul, so the 30 days are counted from the 19th.
        val now = Instant.parse("2026-11-18T16:00:00Z")

        assertThat(expiry.cutoff(now)).isEqualTo(at("2026-10-20T00:00:00"))
        assertThat(LocalDate.ofInstant(expiry.cutoff(now), seoul)).isEqualTo(LocalDate.parse("2026-10-20"))
    }

    @Test
    fun `the number of days and the zone are settings`() {
        val short = BundleExpiry(CombinationSettings(expireDays = 7))
        val utc = BundleExpiry(CombinationSettings(defaultZone = ZoneId.of("UTC")))

        assertThat(short.cutoff(at("2026-11-19T10:00:00"))).isEqualTo(at("2026-11-12T00:00:00"))
        assertThat(utc.cutoff(Instant.parse("2026-11-18T16:00:00Z"))).isEqualTo(Instant.parse("2026-10-19T00:00:00Z"))
    }

    @Test
    fun `a bundle is shown for at least one day`() {
        assertThatThrownBy { CombinationSettings(expireDays = 0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(CombinationSettings().expireDays).isEqualTo(30)
    }
}
