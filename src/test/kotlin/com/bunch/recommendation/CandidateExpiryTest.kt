package com.bunch.recommendation

import com.bunch.recommendation.domain.CandidateExpiry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class CandidateExpiryTest {
    private val expiry = CandidateExpiry()

    @Test
    fun `a bundle whose last photo was added on October 1 is shown through October 31`() {
        val added = kst("2026-10-01T23:59:00")

        assertThat(expiry.isExpired(added, kst("2026-10-31T00:00:00"))).isFalse()
        assertThat(expiry.isExpired(added, kst("2026-10-31T23:59:59"))).isFalse()
    }

    @Test
    fun `a bundle whose last photo was added on October 1 is gone from November 1`() {
        val added = kst("2026-10-01T00:00:00")

        assertThat(expiry.isExpired(added, kst("2026-11-01T00:00:00"))).isTrue()
        assertThat(expiry.isExpired(added, kst("2026-11-01T04:30:00"))).isTrue()
    }

    @Test
    fun `a bundle that came back on November 5 is shown through December 5`() {
        val added = kst("2026-11-05T09:00:00")

        assertThat(expiry.isExpired(added, kst("2026-12-05T23:00:00"))).isFalse()
        assertThat(expiry.isExpired(added, kst("2026-12-06T00:00:00"))).isTrue()
    }

    @Test
    fun `the days are counted in Korean time and not in UTC`() {
        val added = Instant.parse("2026-10-01T15:30:00Z")

        assertThat(expiry.isExpired(added, kst("2026-11-01T12:00:00"))).isFalse()
        assertThat(expiry.isExpired(added, kst("2026-11-02T00:00:00"))).isTrue()
    }

    @Test
    fun `the cutoff is the start of the day thirty days before in Korean time`() {
        assertThat(expiry.cutoff(kst("2026-11-06T13:45:00"))).isEqualTo(kst("2026-10-07T00:00:00"))
    }

    @Test
    fun `a photo added exactly at the cutoff is not expired`() {
        val now = kst("2026-11-06T04:30:00")

        assertThat(expiry.isExpired(expiry.cutoff(now), now)).isFalse()
        assertThat(expiry.isExpired(expiry.cutoff(now).minusMillis(1), now)).isTrue()
    }

    private fun kst(local: String): Instant = Instant.parse("${local}Z").minusSeconds(9 * 3600)
}
