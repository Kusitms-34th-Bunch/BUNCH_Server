package com.bunch.notification

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.domain.BundleParticipant
import com.bunch.bundle.domain.BundleParticipantRepository
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.domain.BundleStatus
import com.bunch.bundle.dto.BundleActor
import com.bunch.bundle.dto.BundleTextRequest
import com.bunch.bundle.service.BundleService
import com.bunch.notification.domain.NotificationRepository
import com.bunch.notification.domain.NotificationType
import com.bunch.notification.service.CommentReminderWorker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@ActiveProfiles("test")
class CommentReminderIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var worker: CommentReminderWorker

    @Autowired
    private lateinit var notifications: NotificationRepository

    @Autowired
    private lateinit var bundles: BundleRepository

    @Autowired
    private lateinit var bunchRows: BunchRepository

    @Autowired
    private lateinit var participants: BundleParticipantRepository

    @Autowired
    private lateinit var bundleService: BundleService

    @Autowired
    private lateinit var transaction: TransactionTemplate

    @MockitoBean
    private lateinit var clock: Clock

    private lateinit var now: Instant

    @BeforeEach
    fun setTime() {
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        `when`(clock.instant()).thenReturn(now)
    }

    @AfterEach
    fun cleanReminderData() {
        try {
            jdbc.update("DELETE FROM bundles")
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS comment_reminder_notification_failure ON notifications")
            jdbc.execute("DROP FUNCTION IF EXISTS comment_reminder_reject_write()")
        }
    }

    @Test
    fun `sends both thresholds when late and never duplicates them`() {
        val fixture = fixture(now.minus(Duration.ofHours(72)))

        assertThat(worker.runOnce(now)).isEqualTo(2)
        assertThat(worker.runOnce(now.plusSeconds(1))).isZero()

        val state = participants.findById(fixture.participantRowId).orElseThrow()
        assertThat(state.reminder24SentAt).isEqualTo(now)
        assertThat(state.reminder72SentAt).isEqualTo(now)
        assertThat(reminders(fixture.participantMemberId)).hasSize(2)
    }

    @Test
    fun `uses participation time rather than bundle creation time`() {
        val fixture = fixture(now.minus(Duration.ofHours(23)))

        assertThat(worker.runOnce(now)).isZero()
        assertThat(worker.runOnce(now.plus(Duration.ofHours(1)))).isEqualTo(1)
        assertThat(reminders(fixture.participantMemberId)).hasSize(1)
    }

    @Test
    fun `reminds the creator when the creator is an unfinished participant`() {
        val fixture = fixture(now.minus(Duration.ofHours(24)), creator = true)

        assertThat(worker.runOnce(now)).isEqualTo(1)
        assertThat(reminders(fixture.participantMemberId)).hasSize(1)
    }

    @Test
    fun `rechecks the late 72 hour threshold without replaying the 24 hour reminder`() {
        val fixture = fixture(now.minus(Duration.ofHours(24)))

        assertThat(worker.runOnce(now)).isEqualTo(1)
        assertThat(worker.runOnce(now.plus(Duration.ofHours(48)))).isEqualTo(1)
        assertThat(worker.runOnce(now.plus(Duration.ofHours(48)).plusSeconds(1))).isZero()

        val state = participants.findById(fixture.participantRowId).orElseThrow()
        assertThat(state.reminder24SentAt).isEqualTo(now)
        assertThat(state.reminder72SentAt).isEqualTo(now.plus(Duration.ofHours(48)))
        assertThat(reminders(fixture.participantMemberId)).hasSize(2)
    }

    @Test
    fun `a nonblank comment permanently cancels remaining reminders`() {
        val fixture = fixture(now.minus(Duration.ofHours(72)))

        bundleService.comment(
            BundleActor(fixture.participantMemberId, "comment-reminder-test"),
            fixture.bunchId,
            fixture.bundleId,
            BundleTextRequest("완료했어요", 0, BundleStatus.DRAFT),
            "comment-reminder-cancel"
        )

        val state = participants.findById(fixture.participantRowId).orElseThrow()
        assertThat(state.remindersCancelledAt).isEqualTo(now)

        bundleService.comment(
            BundleActor(fixture.participantMemberId, "comment-reminder-test"),
            fixture.bunchId,
            fixture.bundleId,
            BundleTextRequest("", 1, BundleStatus.DRAFT),
            "comment-reminder-clear"
        )

        assertThat(participants.findById(fixture.participantRowId).orElseThrow().remindersCancelledAt)
            .isEqualTo(now)
        assertThat(worker.runOnce(now)).isZero()
        assertThat(reminders(fixture.participantMemberId)).isEmpty()
    }

    @Test
    fun `a whitespace-only comment keeps the participant eligible for a reminder`() {
        val fixture = fixture(now.minus(Duration.ofHours(24)))

        bundleService.comment(
            BundleActor(fixture.participantMemberId, "comment-reminder-whitespace-test"),
            fixture.bunchId,
            fixture.bundleId,
            BundleTextRequest(WHITESPACE_ONLY, 0, BundleStatus.DRAFT),
            "comment-reminder-whitespace"
        )

        assertThat(participants.findById(fixture.participantRowId).orElseThrow().remindersCancelledAt)
            .isNull()
        assertThat(worker.runOnce(now)).isEqualTo(1)
        assertThat(reminders(fixture.participantMemberId)).hasSize(1)
    }

    @Test
    fun `skips removed, departed, published, and deleted candidates`() {
        val removed = fixture(now.minus(Duration.ofHours(24)))
        participants.deleteById(removed.participantRowId)
        assertThat(worker.runOnce(now)).isZero()

        val departed = fixture(now.minus(Duration.ofHours(24)))
        jdbc.update(
            "UPDATE bunch_memberships SET status = 'LEFT', ended_at = ? WHERE bunch_id = ? AND member_id = ?",
            Timestamp.from(now),
            departed.bunchId,
            departed.participantMemberId
        )
        assertThat(worker.runOnce(now)).isZero()

        val published = fixture(now.minus(Duration.ofHours(24)))
        published.bundle.status = BundleStatus.PUBLISHED
        bundles.saveAndFlush(published.bundle)
        assertThat(worker.runOnce(now)).isZero()

        val deleted = fixture(now.minus(Duration.ofHours(24)))
        bundles.deleteById(deleted.bundleId)
        assertThat(worker.runOnce(now)).isZero()
    }

    @Test
    fun `rolls back notification and participant state when notification write fails`() {
        val fixture = fixture(now.minus(Duration.ofHours(72)))
        jdbc.execute(
            """CREATE FUNCTION comment_reminder_reject_write() RETURNS trigger LANGUAGE plpgsql AS
                'BEGIN RAISE EXCEPTION ''injected reminder write failure''; END'"""
        )
        jdbc.execute(
            """CREATE TRIGGER comment_reminder_notification_failure
                BEFORE INSERT ON notifications FOR EACH ROW
                EXECUTE FUNCTION comment_reminder_reject_write()"""
        )

        assertThat(worker.runOnce(now)).isZero()

        val state = participants.findById(fixture.participantRowId).orElseThrow()
        assertThat(state.reminder24SentAt).isNull()
        assertThat(state.reminder72SentAt).isNull()
        assertThat(reminders(fixture.participantMemberId)).isEmpty()
    }

    @Test
    fun `two concurrent workers emit one reminder`() {
        val fixture = fixture(now.minus(Duration.ofHours(24)))

        val results = concurrently(List(2) { { worker.runOnce(now) } })

        assertThat(results.map { it.getOrThrow() }.sum()).isEqualTo(1)
        assertThat(reminders(fixture.participantMemberId)).hasSize(1)
        assertThat(participants.findById(fixture.participantRowId).orElseThrow().reminder24SentAt)
            .isEqualTo(now)
    }

    @Test
    fun `rechecks a stale draft candidate after waiting for the bunch lock`() {
        val fixture = fixture(now.minus(Duration.ofHours(24)))
        val executor = Executors.newSingleThreadExecutor()
        var future: java.util.concurrent.Future<Int>? = null

        try {
            transaction.executeWithoutResult {
                bunchRows.findLocked(fixture.bunchId)
                future = executor.submit(Callable { worker.runOnce(now) })
                awaitWorkerBlocked()

                val bundle = bundles.findLocked(fixture.bundleId) ?: error("fixture bundle disappeared")
                bundle.status = BundleStatus.PUBLISHED
                bundle.publishedAt = now
                bundles.flush()
            }

            assertThat(requireNotNull(future).get(10, TimeUnit.SECONDS)).isZero()
            assertThat(reminders(fixture.participantMemberId)).isEmpty()
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun fixture(joinedAt: Instant, creator: Boolean = false): Fixture {
        val owner = member()
        val participant = if (creator) owner else member()
        val bunchId = create(owner).id
        if (!creator) join(participant, issue(owner, bunchId))
        val bundle = bundles.saveAndFlush(
            Bundle(
                bunchId = bunchId,
                createdBy = owner,
                title = "리마인드 테스트",
                createdAt = now.minus(Duration.ofDays(7)),
                updatedAt = now.minus(Duration.ofDays(7))
            )
        )
        val participantRow = participants.saveAndFlush(
            BundleParticipant(bundleId = bundle.id, memberId = participant, joinedAt = joinedAt)
        )
        return Fixture(participant, participantRow.id, bunchId, bundle.id, bundle)
    }

    private fun reminders(memberId: Long) = notifications.findAll()
        .filter { it.recipientMemberId == memberId && it.type == NotificationType.COMMENT_REMINDER }

    private fun awaitWorkerBlocked() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val blocked = jdbc.queryForObject(
                """SELECT count(*) FROM pg_stat_activity activity
                    WHERE activity.pid <> pg_backend_pid()
                    AND pg_backend_pid() = ANY(pg_blocking_pids(activity.pid))""",
                Long::class.java
            ) ?: 0
            if (blocked > 0) return
            Thread.sleep(10)
        }
        error("Reminder worker did not wait on the bunch row lock")
    }

    private data class Fixture(
        val participantMemberId: Long,
        val participantRowId: Long,
        val bunchId: Long,
        val bundleId: Long,
        val bundle: Bundle
    )

    companion object {
        private const val WHITESPACE_ONLY =
            "\u0009\u000A\u000B\u000C\u000D\u001C\u001D\u001E\u001F\u0020\u00A0\u1680" +
                "\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200A" +
                "\u2028\u2029\u202F\u205F\u3000"
    }
}
