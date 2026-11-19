package com.bunch.recommendation

import com.bunch.recommendation.domain.RecommendedBundleQueueRepository
import com.bunch.recommendation.service.RecommendedBundleExpiration
import com.bunch.recommendation.service.RecommendedBundleRecalculation
import com.bunch.recommendation.service.RecommendedBundleScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.time.Instant

/** The scheduler of the recommended bundles with the queue and the calculation made by hand (no database). */
class RecommendedBundleSchedulerTest {
    private val queue = mock(RecommendedBundleQueueRepository::class.java)
    private val recalculation = mock(RecommendedBundleRecalculation::class.java)
    private val expiration = mock(RecommendedBundleExpiration::class.java)
    private val now = Instant.parse("2026-10-19T12:00:00Z")

    private fun scheduler(enabled: Boolean = true, batchSize: Int = 20) =
        RecommendedBundleScheduler(queue, recalculation, expiration, enabled, batchSize)

    @Test
    fun `every bunch that is due is calculated, with the time it was asked at`() {
        doReturn(listOf(1L, 2L, 3L)).`when`(queue).findDueBunchIds(now, 20)

        val looked = scheduler().runOnce(now)

        assertThat(looked).isEqualTo(3)
        listOf(1L, 2L, 3L).forEach { verify(recalculation).recalculate(it, now) }
    }

    @Test
    fun `the number of bunches of a run is the size of the batch`() {
        doReturn(listOf(1L, 2L)).`when`(queue).findDueBunchIds(now, 2)

        assertThat(scheduler(batchSize = 2).runOnce(now)).isEqualTo(2)
        verify(queue).findDueBunchIds(now, 2)
    }

    @Test
    fun `a bunch that fails does not stop the others, and is not removed from the queue`() {
        doReturn(listOf(1L, 2L, 3L)).`when`(queue).findDueBunchIds(now, 20)
        doThrow(IllegalStateException("broken")).`when`(recalculation).recalculate(2L, now)

        val looked = scheduler().runOnce(now)

        assertThat(looked).isEqualTo(3)
        verify(recalculation).recalculate(1L, now)
        verify(recalculation).recalculate(3L, now)
    }

    @Test
    fun `no bunch is due, so nothing is calculated`() {
        doReturn(emptyList<Long>()).`when`(queue).findDueBunchIds(now, 20)

        assertThat(scheduler().runOnce(now)).isZero()
        verifyNoInteractions(recalculation)
    }

    @Test
    fun `while the switch is off the queue is not even read`() {
        assertThat(scheduler(enabled = false).runOnce(now)).isZero()

        verifyNoInteractions(queue)
        verifyNoInteractions(recalculation)
    }

    // --- the expiry (4.2.9 rule 4) ---

    @Test
    fun `the expired bundles are deleted once with the time it was asked at`() {
        doReturn(3).`when`(expiration).expire(now)

        assertThat(scheduler().runExpiry(now)).isEqualTo(3)
        verify(expiration).expire(now)
    }

    @Test
    fun `a day with nothing expired deletes nothing`() {
        doReturn(0).`when`(expiration).expire(now)

        assertThat(scheduler().runExpiry(now)).isZero()
    }

    @Test
    fun `while the switch is off nothing expires`() {
        assertThat(scheduler(enabled = false).runExpiry(now)).isZero()

        verifyNoInteractions(expiration)
    }
}
