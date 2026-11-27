package com.bunch.recommendation

import com.bunch.recommendation.domain.RecommendedBundleQueueRepository
import com.bunch.recommendation.domain.RecommendedBundleRepository
import com.bunch.recommendation.service.RecommendedBundleBackfill
import com.bunch.recommendation.service.RecommendedBundleScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * The first calculation of the photos that were in the photo boxes before the new recommended bundles were switched on
 * (photo box 4.2.1), with the switch on, on a real PostgreSQL.
 */
@SpringBootTest(properties = ["reco.combination.enabled=true"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecommendedBundleBackfillIntegrationTest : RecommendationIntegrationSupport() {
    @Autowired
    private lateinit var backfill: RecommendedBundleBackfill

    @Autowired
    private lateinit var queue: RecommendedBundleQueueRepository

    @Autowired
    private lateinit var scheduler: RecommendedBundleScheduler

    @Autowired
    private lateinit var bundles: RecommendedBundleRepository

    @Autowired
    private lateinit var clock: Clock

    private val firstDay = LocalDate.parse("2026-10-19")
    private val secondDay = LocalDate.parse("2026-10-20")

    @AfterEach
    fun cleanNewFixtures() {
        jdbc.update("DELETE FROM recommended_bundle_recalc_queue")
        jdbc.update("DELETE FROM recommended_bundle_photos")
        jdbc.update("DELETE FROM recommended_bundles")
    }

    @Test
    fun `every day of every bunch with dated photos is queued, and a day with no date or no photo is not`() {
        val (ownerA, bunchA) = bunchWithOwner()
        insertPhoto(bunchA, "2026-10-19", "10:00", uploaders = listOf(ownerA))
        insertPhoto(bunchA, "2026-10-19", "11:00", uploaders = listOf(ownerA))
        insertPhoto(bunchA, "2026-10-20", "10:00", uploaders = listOf(ownerA))
        val (ownerB, bunchB) = bunchWithOwner()
        insertPhoto(bunchB, "2026-10-19", "10:00", uploaders = listOf(ownerB))
        val (ownerC, bunchC) = bunchWithOwner()
        insertPhoto(bunchC, null, uploaders = listOf(ownerC))
        insertPhoto(bunchC, "2026-10-21", "10:00", status = "PENDING", uploaders = listOf(ownerC))
        bunchWithOwner()

        val written = backfill.run()

        assertThat(written).isEqualTo(3)
        assertThat(queuedDays(bunchA)).containsExactly(firstDay, secondDay)
        assertThat(queuedDays(bunchB)).containsExactly(firstDay)
        assertThat(queuedDays(bunchC)).isEmpty()
    }

    @Test
    fun `the days are due at once, so the scheduler calculates them`() {
        val (ownerId, bunchId) = bunchWithOwner()
        gathering(ownerId, bunchId)

        backfill.run()

        val due = queue.findDueDays(bunchId, Instant.now().plusSeconds(1))
        assertThat(due.map { it.dayKey }).containsExactly(firstDay)
    }

    @Test
    fun `the photos that were there before are calculated into a recommended bundle`() {
        val (ownerId, bunchId) = bunchWithOwner()
        gathering(ownerId, bunchId)
        assertThat(bundles.findByBunchId(bunchId)).isEmpty()

        backfill.run()
        scheduler.runOnce(Instant.now().plusSeconds(1))

        assertThat(bundles.findByBunchId(bunchId)).hasSize(1)
        assertThat(queuedDays(bunchId)).isEmpty()
    }

    @Test
    fun `run again it makes no second row and keeps the bundle that was made`() {
        val (ownerId, bunchId) = bunchWithOwner()
        gathering(ownerId, bunchId)
        backfill.run()
        scheduler.runOnce(Instant.now().plusSeconds(1))
        val first = bundles.findByBunchId(bunchId).single()

        backfill.run()
        scheduler.runOnce(Instant.now().plusSeconds(1))

        assertThat(queuedDays(bunchId)).isEmpty()
        val second = bundles.findByBunchId(bunchId).single()
        assertThat(second.id).isEqualTo(first.id)
        assertThat(second.createdAt).isEqualTo(first.createdAt)
    }

    @Test
    fun `run twice before the calculation it still queues one row for a day`() {
        val (ownerId, bunchId) = bunchWithOwner()
        gathering(ownerId, bunchId)

        backfill.run()
        backfill.run()

        assertThat(queuedDays(bunchId)).containsExactly(firstDay)
    }

    @Test
    fun `a bundle whose photos were all added more than 30 days ago is not made, as in every calculation`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photoIds = gathering(ownerId, bunchId)
        jdbc.update(
            "UPDATE photos SET created_at = ? WHERE id IN (${photoIds.joinToString()})",
            Timestamp.from(Instant.now().minusSeconds(40 * 86_400L))
        )

        backfill.run()
        scheduler.runOnce(Instant.now().plusSeconds(1))

        assertThat(bundles.findByBunchId(bunchId)).isEmpty()
        assertThat(queuedDays(bunchId)).isEmpty()
    }

    @Test
    fun `with the switch off nothing is queued`() {
        val (ownerId, bunchId) = bunchWithOwner()
        gathering(ownerId, bunchId)
        val off = RecommendedBundleBackfill(queue, clock, enabled = false, backfillEnabled = true)

        assertThat(off.run()).isZero()
        off.runAtStart()

        assertThat(queuedDays(bunchId)).isEmpty()
    }

    @Test
    fun `the start runs it only when the backfill is switched on`() {
        val (ownerId, bunchId) = bunchWithOwner()
        gathering(ownerId, bunchId)

        RecommendedBundleBackfill(queue, clock, enabled = true, backfillEnabled = false).runAtStart()
        assertThat(queuedDays(bunchId)).isEmpty()

        RecommendedBundleBackfill(queue, clock, enabled = true, backfillEnabled = true).runAtStart()
        assertThat(queuedDays(bunchId)).containsExactly(firstDay)
    }

    // A lunch of 8 photos on the first day, one every ten minutes, uploaded by two members in turn.
    private fun gathering(ownerId: Long, bunchId: Long): List<Long> {
        val other = joined(ownerId, bunchId)
        return (0 until 8).map { i ->
            val time = LocalTime.of(10, 0).plusMinutes(10L * i).toString()
            insertPhoto(bunchId, "2026-10-19", time, uploaders = listOf(if (i % 2 == 0) ownerId else other))
        }
    }

    private fun queuedDays(bunchId: Long): List<LocalDate> = jdbc.query(
        "SELECT day_key FROM recommended_bundle_recalc_queue WHERE bunch_id = ? ORDER BY day_key",
        { rs, _ -> rs.getDate("day_key").toLocalDate() },
        bunchId
    )
}
