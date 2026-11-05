package com.bunch.recommendation

import com.bunch.global.image.ProfileImageStorage
import com.bunch.photo.service.PhotoChangedEvent
import com.bunch.recommendation.service.RecommendationScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecommendationChangeIntegrationTest : RecommendationIntegrationSupport() {
    @Autowired
    private lateinit var events: ApplicationEventPublisher

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var scheduler: RecommendationScheduler

    @MockitoBean
    private lateinit var storage: ProfileImageStorage

    private val day = LocalDate.of(2026, 10, 19)
    private val changedAt = Instant.parse("2026-11-01T10:00:00Z")
    private val quietTime = Duration.ofMinutes(5)

    @Test
    fun `a photo change notes its date in the same transaction`() {
        val (_, bunchId) = bunchWithOwner()

        inTransaction { events.publishEvent(PhotoChangedEvent(bunchId, day, photoAdded = true)) }
        inTransaction { events.publishEvent(PhotoChangedEvent(bunchId, day.plusDays(1), photoAdded = false)) }

        assertThat(noted(bunchId).keys).containsExactlyInAnyOrder(day, day.plusDays(1))
    }

    @Test
    fun `a change without a date is not noted`() {
        val (_, bunchId) = bunchWithOwner()

        inTransaction { events.publishEvent(PhotoChangedEvent(bunchId, null, photoAdded = false)) }

        assertThat(noted(bunchId)).isEmpty()
    }

    @Test
    fun `a change that is rolled back is not noted`() {
        val (_, bunchId) = bunchWithOwner()

        runCatching {
            inTransaction {
                events.publishEvent(PhotoChangedEvent(bunchId, day, photoAdded = true))
                error("The photo change failed")
            }
        }

        assertThat(noted(bunchId)).isEmpty()
    }

    @Test
    fun `a date that changed again has one row with the newer time`() {
        val (_, bunchId) = bunchWithOwner()
        inTransaction { events.publishEvent(PhotoChangedEvent(bunchId, day, photoAdded = true)) }
        setChangedAt(bunchId, day, changedAt)

        inTransaction { events.publishEvent(PhotoChangedEvent(bunchId, day, photoAdded = false)) }

        assertThat(noted(bunchId)).hasSize(1)
        assertThat(noted(bunchId).getValue(day)).isAfter(changedAt)
    }

    @Test
    fun `deleting photos notes the date of the deleted photos`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photo = insertPhoto(bunchId, "2026-10-19", "19:00", uploaders = listOf(ownerId))

        mvc.perform(
            post("/api/v1/bunches/$bunchId/photos/delete")
                .header(HttpHeaders.AUTHORIZATION, bearer(ownerId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"photoIds":[$photo]}""")
        ).andExpect(status().isOk)

        assertThat(noted(bunchId).keys).containsExactly(day)
    }

    @Test
    fun `changing the date of a photo notes the old and the new date`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photo = insertPhoto(bunchId, "2026-10-19", "19:00", uploaders = listOf(ownerId))

        mvc.perform(
            post("/api/v1/bunches/$bunchId/photos/date")
                .header(HttpHeaders.AUTHORIZATION, bearer(ownerId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"photoIds":[$photo],"capturedDate":"2026-10-22"}""")
        ).andExpect(status().isOk)

        assertThat(noted(bunchId).keys).containsExactlyInAnyOrder(day, LocalDate.of(2026, 10, 22))
    }

    @Test
    fun `a bunch whose photos were quiet for five minutes is calculated once and its notes are removed`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val other = joined(ownerId, bunchId)
        shoot(bunchId, "2026-10-19", "19:00", 8, listOf(ownerId, other))
        note(bunchId, day, changedAt)

        assertThat(scheduler.runOnce(changedAt.plus(quietTime))).isEqualTo(1)

        assertThat(candidates(bunchId)).hasSize(1)
        assertThat(noted(bunchId)).isEmpty()
        assertThat(scheduler.runOnce(changedAt.plus(quietTime))).isZero()
        assertThat(candidates(bunchId)).hasSize(1)
    }

    @Test
    fun `a bunch is not calculated before five quiet minutes have passed`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val other = joined(ownerId, bunchId)
        shoot(bunchId, "2026-10-19", "19:00", 8, listOf(ownerId, other))
        note(bunchId, day, changedAt)

        assertThat(scheduler.runOnce(changedAt.plus(quietTime).minusSeconds(1))).isZero()

        assertThat(candidates(bunchId)).isEmpty()
        assertThat(noted(bunchId).keys).containsExactly(day)
    }

    @Test
    fun `the last change of the bunch decides when all its noted dates are calculated`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val other = joined(ownerId, bunchId)
        shoot(bunchId, "2026-10-19", "19:00", 8, listOf(ownerId, other))
        shoot(bunchId, "2026-10-25", "19:00", 8, listOf(ownerId, other))
        val now = changedAt.plus(Duration.ofMinutes(10))
        note(bunchId, day, now.minus(Duration.ofMinutes(10)))
        note(bunchId, LocalDate.of(2026, 10, 25), now.minus(Duration.ofMinutes(1)))

        assertThat(scheduler.runOnce(now)).isZero()
        assertThat(candidates(bunchId)).isEmpty()

        assertThat(scheduler.runOnce(now.plus(quietTime))).isEqualTo(1)
        assertThat(candidates(bunchId)).hasSize(2)
        assertThat(noted(bunchId)).isEmpty()
    }

    @Test
    fun `bunches are calculated one by one by their own last change`() {
        val (firstOwner, firstBunch) = bunchWithOwner()
        val (secondOwner, secondBunch) = bunchWithOwner()
        shoot(firstBunch, "2026-10-19", "19:00", 8, listOf(firstOwner, joined(firstOwner, firstBunch)))
        shoot(secondBunch, "2026-10-19", "19:00", 8, listOf(secondOwner, joined(secondOwner, secondBunch)))
        val now = changedAt.plus(Duration.ofMinutes(10))
        note(firstBunch, day, now.minus(Duration.ofMinutes(6)))
        note(secondBunch, day, now.minus(Duration.ofMinutes(1)))

        assertThat(scheduler.runOnce(now)).isEqualTo(1)

        assertThat(candidates(firstBunch)).hasSize(1)
        assertThat(candidates(secondBunch)).isEmpty()
        assertThat(noted(secondBunch).keys).containsExactly(day)
    }

    @Test
    fun `a change that came in after the bunch was picked waits for its own quiet time`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val other = joined(ownerId, bunchId)
        shoot(bunchId, "2026-10-19", "19:00", 8, listOf(ownerId, other))
        note(bunchId, day, changedAt.plusSeconds(1))

        recommendation.recalculateIfQuiet(bunchId, changedAt)

        assertThat(candidates(bunchId)).isEmpty()
        assertThat(noted(bunchId).keys).containsExactly(day)

        recommendation.recalculateIfQuiet(bunchId, changedAt.plusSeconds(1))

        assertThat(candidates(bunchId)).hasSize(1)
        assertThat(noted(bunchId)).isEmpty()
    }

    @Test
    fun `deleting photos until fewer than six are left removes the candidate after the quiet time`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val other = joined(ownerId, bunchId)
        val photos = shoot(bunchId, "2026-10-19", "19:00", 8, listOf(ownerId, other))
        recalculate(bunchId, "2026-10-19")
        assertThat(candidates(bunchId)).hasSize(1)

        mvc.perform(
            post("/api/v1/bunches/$bunchId/photos/delete")
                .header(HttpHeaders.AUTHORIZATION, bearer(ownerId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"photoIds":[${photos.take(3).joinToString(",")}]}""")
        ).andExpect(status().isOk)
        setChangedAt(bunchId, day, changedAt)

        scheduler.runOnce(changedAt.plus(quietTime))

        assertThat(candidates(bunchId)).isEmpty()
        assertThat(noted(bunchId)).isEmpty()
    }

    private fun inTransaction(action: () -> Unit) {
        TransactionTemplate(transactionManager).execute { action() }
    }

    private fun noted(bunchId: Long): Map<LocalDate, Instant> = jdbc.query(
        "SELECT captured_date, changed_at FROM recommendation_dirty_dates WHERE bunch_id = ?",
        { rs, _ -> rs.getDate("captured_date").toLocalDate() to rs.getTimestamp("changed_at").toInstant() },
        bunchId
    ).toMap()

    private fun note(bunchId: Long, date: LocalDate, at: Instant) {
        jdbc.update(
            "INSERT INTO recommendation_dirty_dates (bunch_id, captured_date, changed_at) VALUES (?, ?, ?)",
            bunchId,
            java.sql.Date.valueOf(date),
            Timestamp.from(at)
        )
    }

    private fun setChangedAt(bunchId: Long, date: LocalDate, at: Instant) {
        jdbc.update(
            "UPDATE recommendation_dirty_dates SET changed_at = ? WHERE bunch_id = ? AND captured_date = ?",
            Timestamp.from(at),
            bunchId,
            java.sql.Date.valueOf(date)
        )
    }
}
