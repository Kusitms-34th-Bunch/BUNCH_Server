package com.bunch.bunch

import com.bunch.bunch.domain.BunchProfileReportRepository
import com.bunch.bunch.domain.BunchReportReason
import com.bunch.bunch.dto.BunchReportRequest
import com.bunch.bunch.dto.BunchReportResult
import com.bunch.bunch.service.BunchSocialService
import com.bunch.global.exception.BusinessException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

@SpringBootTest
@ActiveProfiles("test")
class BunchReportLimitIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var social: BunchSocialService

    @Autowired
    private lateinit var reports: BunchProfileReportRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @MockitoBean
    private lateinit var clock: Clock

    private val start = Instant.parse("2026-11-06T00:00:00.123456Z")
    private val day = Duration.ofHours(24)

    @BeforeEach
    fun setTime() = at(start)

    @Test
    fun `same member may submit two independent reports but not a third`() {
        val owner = member()
        val viewer = create(owner).id
        val target = create(member()).id
        val first = social.report(owner, viewer, BunchReportRequest(target, BunchReportReason.SPAM, "first"))
        val second = social.report(owner, viewer, BunchReportRequest(target, BunchReportReason.OTHER, "second"))

        assertThat(second.reportId).isNotEqualTo(first.reportId)
        assertThat(reports.findById(first.reportId).orElseThrow().detail).isEqualTo("first")
        assertThat(reports.findById(second.reportId).orElseThrow().detail).isEqualTo("second")
        quotaFailure { social.report(owner, viewer, request(target)) }
        assertThat(count("bunch_profile_reports")).isEqualTo(2)
    }

    @Test
    fun `second and rejected requests do not extend the first anchored window`() {
        val owner = member()
        val viewer = create(owner).id
        val target = create(member()).id
        at(start.plusNanos(789))
        val first = social.report(owner, viewer, request(target))
        assertThat(reports.findById(first.reportId).orElseThrow().createdAt).isEqualTo(start)

        at(start.plus(day).minusNanos(1_000))
        val second = social.report(owner, viewer, request(target))
        assertThat(windowStart(first.reportId)).isEqualTo(start)
        assertThat(windowStart(second.reportId)).isEqualTo(start)
        quotaFailure { social.report(owner, viewer, request(target)) }

        val boundary = start.plus(day)
        at(boundary)
        val next = social.report(owner, viewer, request(target))
        at(boundary.plusNanos(1_000))
        val nextSecond = social.report(owner, viewer, request(target))
        assertThat(windowStart(next.reportId)).isEqualTo(boundary)
        assertThat(windowStart(nextSecond.reportId)).isEqualTo(boundary)
        quotaFailure { social.report(owner, viewer, request(target)) }

        val muchLater = start.plus(Duration.ofDays(4)).plusSeconds(17)
        at(muchLater)
        val later = social.report(owner, viewer, request(target))
        assertThat(windowStart(later.reportId)).isEqualTo(muchLater)
        assertThat(windowStart(first.reportId)).isEqualTo(start)
        assertThat(count("bunch_profile_reports")).isEqualTo(5)
    }

    @Test
    fun `review completion does not restore quota and each report retains its own state`() {
        val owner = member()
        val viewer = create(owner).id
        val target = create(member()).id
        val first = social.report(owner, viewer, request(target))
        jdbc.update(
            "UPDATE bunch_profile_reports SET status = 'DISMISSED', reviewed_at = ? WHERE id = ?",
            Timestamp.from(start.plusSeconds(10)),
            first.reportId
        )
        at(start.plusSeconds(20))
        val second = social.report(owner, viewer, request(target))
        assertThat(reports.findById(second.reportId).orElseThrow().status).isEqualTo("RECEIVED")
        assertThat(reports.findById(second.reportId).orElseThrow().reviewedAt).isNull()
        jdbc.update(
            "UPDATE bunch_profile_reports SET status = 'ACTIONED', reviewed_at = ? WHERE id = ?",
            Timestamp.from(start.plusSeconds(30)),
            second.reportId
        )
        quotaFailure { social.report(owner, viewer, request(target)) }
        assertThat(reports.findById(first.reportId).orElseThrow().status).isEqualTo("DISMISSED")
        assertThat(count("bunch_profile_reports")).isEqualTo(2)
    }

    @Test
    fun `different reporting bunches and targets have independent quotas`() {
        val owner = member()
        val viewer = create(owner).id
        val otherViewer = create(owner).id
        val target = create(member()).id
        val otherTarget = create(member()).id
        repeat(2) { social.report(owner, viewer, request(target)) }
        quotaFailure { social.report(owner, viewer, request(target)) }
        repeat(2) { social.report(owner, viewer, request(otherTarget)) }
        repeat(2) { social.report(owner, otherViewer, request(target)) }
        assertThat(count("bunch_profile_reports")).isEqualTo(6)
    }

    @Test
    fun `backfilled recent reports count once and old reports start a fresh window`() {
        val owner = member()
        val viewer = create(owner).id
        val recentTarget = create(member()).id
        val oldTarget = create(member()).id
        val recent = start.minusSeconds(3_600)
        val old = start.minus(Duration.ofDays(2))
        listOf(recentTarget to recent, oldTarget to old).forEach { (target, createdAt) ->
            jdbc.update(
                """INSERT INTO bunch_profile_reports
                    (reporter_bunch_id, target_bunch_id, reported_by_member_id, reason,
                     status, created_at, window_started_at)
                    VALUES (?, ?, ?, 'SPAM', 'DISMISSED', ?, ?)""",
                viewer,
                target,
                owner,
                Timestamp.from(createdAt),
                Timestamp.from(createdAt)
            )
        }

        val recentSecond = social.report(owner, viewer, request(recentTarget))
        assertThat(windowStart(recentSecond.reportId)).isEqualTo(recent)
        quotaFailure { social.report(owner, viewer, request(recentTarget)) }

        repeat(2) {
            val accepted = social.report(owner, viewer, request(oldTarget))
            assertThat(windowStart(accepted.reportId)).isEqualTo(start)
        }
        quotaFailure { social.report(owner, viewer, request(oldTarget)) }
        assertThat(count("bunch_profile_reports")).isEqualTo(5)
    }

    @Test
    fun `three concurrent members share exactly two slots`() {
        val (viewer, target, actors) = group()
        val outcomes = concurrently(actors.map { actor -> { social.report(actor, viewer, request(target)) } })
        assertQuotaOutcomes(outcomes, successes = 2)
        assertThat(count("bunch_profile_reports")).isEqualTo(2)
    }

    @Test
    fun `concurrent members cannot exceed one remaining slot`() {
        val (viewer, target, actors) = group()
        social.report(actors[0], viewer, request(target))
        val outcomes = concurrently(actors.drop(1).map { actor -> { social.report(actor, viewer, request(target)) } })
        assertQuotaOutcomes(outcomes, successes = 1)
        assertThat(count("bunch_profile_reports")).isEqualTo(2)
    }

    @Test
    fun `concurrent requests at expiration open just one new two-slot window`() {
        val (viewer, target, actors) = group()
        repeat(2) { social.report(actors[0], viewer, request(target)) }
        at(start.plus(day))
        val outcomes = concurrently(actors.map { actor -> { social.report(actor, viewer, request(target)) } })
        assertQuotaOutcomes(outcomes, successes = 2)
        outcomes.filter { it.isSuccess }.forEach {
            assertThat(windowStart(it.getOrThrow().reportId)).isEqualTo(start.plus(day))
        }
        assertThat(count("bunch_profile_reports")).isEqualTo(4)
    }

    @Test
    fun `rolled back first request does not anchor the next accepted window`() {
        val owner = member()
        val viewer = create(owner).id
        val target = create(member()).id
        assertThatThrownBy {
            TransactionTemplate(transactionManager).executeWithoutResult {
                social.report(owner, viewer, request(target))
                error("caller rollback")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(count("bunch_profile_reports")).isZero()

        val later = start.plusSeconds(3_600)
        at(later)
        val first = social.report(owner, viewer, request(target))
        val second = social.report(owner, viewer, request(target))
        assertThat(windowStart(first.reportId)).isEqualTo(later)
        assertThat(windowStart(second.reportId)).isEqualTo(later)
    }

    @Test
    fun `rolled back second request leaves its slot available in the original window`() {
        val owner = member()
        val viewer = create(owner).id
        val target = create(member()).id
        social.report(owner, viewer, request(target))
        at(start.plusSeconds(3_600))
        assertThatThrownBy {
            TransactionTemplate(transactionManager).executeWithoutResult {
                social.report(owner, viewer, request(target))
                error("caller rollback")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(count("bunch_profile_reports")).isEqualTo(1)

        at(start.plusSeconds(7_200))
        val second = social.report(owner, viewer, request(target))
        assertThat(windowStart(second.reportId)).isEqualTo(start)
        quotaFailure { social.report(owner, viewer, request(target)) }
        assertThat(count("bunch_profile_reports")).isEqualTo(2)
    }

    @Test
    fun `a request waiting on the bunch lock uses the time after acquiring it`() {
        val owner = member()
        val viewer = create(owner).id
        val target = create(member()).id
        repeat(2) { social.report(owner, viewer, request(target)) }
        at(start.plus(day).minusSeconds(1))
        val executor = Executors.newSingleThreadExecutor()
        try {
            lateinit var waiting: Future<BunchReportResult>
            TransactionTemplate(transactionManager).executeWithoutResult {
                jdbc.queryForList("SELECT id FROM bunches WHERE id = ? FOR UPDATE", viewer)
                val holderPid = jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!
                waiting = executor.submit<BunchReportResult> { social.report(owner, viewer, request(target)) }
                awaitBlockedBy(holderPid)
                at(start.plus(day))
            }
            assertThat(windowStart(waiting.get(10, TimeUnit.SECONDS).reportId)).isEqualTo(start.plus(day))
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun at(now: Instant) {
        `when`(clock.instant()).thenReturn(now)
    }

    private fun request(target: Long) = BunchReportRequest(target, BunchReportReason.SPAM)

    private fun windowStart(id: Long): Instant = jdbc.queryForObject(
        "SELECT window_started_at FROM bunch_profile_reports WHERE id = ?",
        Timestamp::class.java,
        id
    )!!.toInstant()

    private fun quotaFailure(action: () -> Any?) {
        assertThatThrownBy { action() }.isInstanceOfSatisfying(BusinessException::class.java) {
            assertThat(it.errorCode.code).isEqualTo("BUNCH_011")
        }
    }

    private fun group(): Triple<Long, Long, List<Long>> {
        val actors = List(3) { member() }
        val viewer = create(actors[0]).id
        actors.drop(1).forEach { join(it, issue(actors[0], viewer)) }
        return Triple(viewer, create(member()).id, actors)
    }

    private fun assertQuotaOutcomes(outcomes: List<Result<BunchReportResult>>, successes: Int) {
        val accepted = outcomes.filter { it.isSuccess }.map { it.getOrThrow().reportId }
        assertThat(accepted).hasSize(successes).doesNotHaveDuplicates()
        val rejected = outcomes.filter { it.isFailure }.map { it.exceptionOrNull() }
        assertThat(rejected).hasSize(outcomes.size - successes)
        rejected.forEach { error ->
            assertThat(error).isInstanceOf(BusinessException::class.java)
            assertThat((error as BusinessException).errorCode.code).isEqualTo("BUNCH_011")
        }
    }

    private fun awaitBlockedBy(holderPid: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val blocked = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid)))",
                Boolean::class.java,
                holderPid
            )!!
            if (blocked) return
            Thread.sleep(20)
        }
        error("report did not wait for the held bunch lock")
    }
}
