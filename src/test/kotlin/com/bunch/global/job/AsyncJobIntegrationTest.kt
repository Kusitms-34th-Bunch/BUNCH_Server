package com.bunch.global.job

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// The worker is built here with test handlers instead of importing a test configuration,
// so this class shares its Spring context (and DB connections) with the other integration tests.
@SpringBootTest
@ActiveProfiles("test")
class AsyncJobIntegrationTest {
    @Autowired
    private lateinit var jobService: AsyncJobService

    @Autowired
    private lateinit var jobs: AsyncJobRepository

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private val recorders = listOf(
        RecordingHandler("TEST_OK") { _, _ -> },
        // The pause widens the window in which two workers could run the same job.
        RecordingHandler("TEST_SLOW") { _, _ -> Thread.sleep(20) },
        RecordingHandler("TEST_FAIL") { _, _ -> throw IllegalStateException(SECRET) },
        RecordingHandler("TEST_FLAKY") { _, call -> if (call <= 2) throw IllegalStateException(SECRET) }
    )

    private lateinit var worker: AsyncJobWorker

    private val transactions by lazy { TransactionTemplate(transactionManager) }

    @BeforeEach
    fun prepare() {
        jdbc.update("DELETE FROM async_jobs")
        worker = AsyncJobWorker(
            jobs = jobs,
            handlers = recorders,
            transactionManager = transactionManager,
            batchSize = 10,
            leaseSeconds = 300,
            maxAttempts = 5,
            retryBaseSeconds = 10
        )
    }

    @AfterEach
    fun cleanJobs() {
        jdbc.update("DELETE FROM async_jobs")
    }

    @Test
    fun `enqueue keeps one unfinished job per type and target`() {
        assertThat(enqueue("TEST_OK", "a")).isTrue()
        assertThat(enqueue("TEST_OK", "a")).isFalse()
        assertThat(enqueue("TEST_OK", "b")).isTrue()
        assertThat(enqueue("TEST_SLOW", "a")).isTrue()
        assertThat(count()).isEqualTo(3)

        assertThat(worker.runOnce(later())).isEqualTo(3)
        assertThat(enqueue("TEST_OK", "a")).isTrue()
        assertThat(count()).isEqualTo(4)
    }

    @Test
    fun `a due job runs once and is marked succeeded`() {
        enqueue("TEST_OK", "a")

        assertThat(worker.runOnce(later())).isEqualTo(1)
        assertThat(worker.runOnce(later())).isZero()

        val row = row("TEST_OK", "a")
        assertThat(row.status).isEqualTo("SUCCEEDED")
        assertThat(row.attempts).isEqualTo(1)
        assertThat(row.leaseUntil).isNull()
        assertThat(handler("TEST_OK").calls("a")).isEqualTo(1)
    }

    @Test
    fun `a delayed job waits for its time`() {
        enqueue("TEST_OK", "a", Duration.ofSeconds(60))
        val now = later()

        assertThat(worker.runOnce(now)).isZero()
        assertThat(row("TEST_OK", "a").status).isEqualTo("PENDING")
        assertThat(worker.runOnce(now.plusSeconds(61))).isEqualTo(1)
        assertThat(row("TEST_OK", "a").status).isEqualTo("SUCCEEDED")
    }

    @Test
    fun `a failing job backs off with doubling delays and ends as failed`() {
        enqueue("TEST_FAIL", "f")
        var now = later()
        val delays = listOf(10L, 20L, 40L, 80L)

        repeat(5) { index ->
            assertThat(worker.runOnce(now)).isEqualTo(1)
            val row = row("TEST_FAIL", "f")
            assertThat(row.attempts).isEqualTo(index + 1)
            assertThat(row.leaseUntil).isNull()
            // Only the exception class is stored, never its message.
            assertThat(row.lastErrorCode).isEqualTo("IllegalStateException")

            if (index < delays.size) {
                assertThat(row.status).isEqualTo("PENDING")
                assertThat(row.nextAttemptAt).isEqualTo(now.plusSeconds(delays[index]))
                assertThat(worker.runOnce(now.plusSeconds(delays[index] - 1))).isZero()
                now = row.nextAttemptAt
            } else {
                assertThat(row.status).isEqualTo("FAILED")
            }
        }

        assertThat(handler("TEST_FAIL").calls("f")).isEqualTo(5)
        assertThat(worker.runOnce(now.plusSeconds(100_000))).isZero()
        assertThat(dumpRows()).doesNotContain(SECRET)
    }

    @Test
    fun `a job that fails twice and then succeeds ends as succeeded`() {
        enqueue("TEST_FLAKY", "x")
        var now = later()

        repeat(3) {
            assertThat(worker.runOnce(now)).isEqualTo(1)
            val current = row("TEST_FLAKY", "x")
            if (current.status == "PENDING") now = current.nextAttemptAt
        }

        val row = row("TEST_FLAKY", "x")
        assertThat(row.status).isEqualTo("SUCCEEDED")
        assertThat(row.attempts).isEqualTo(3)
        assertThat(handler("TEST_FLAKY").calls("x")).isEqualTo(3)
    }

    @Test
    fun `a job without a handler fails at once`() {
        enqueue("NO_SUCH_TYPE", "a")

        assertThat(worker.runOnce(later())).isEqualTo(1)

        val row = row("NO_SUCH_TYPE", "a")
        assertThat(row.status).isEqualTo("FAILED")
        assertThat(row.attempts).isEqualTo(1)
        assertThat(row.lastErrorCode).isEqualTo(AsyncJobWorker.UNKNOWN_JOB_TYPE)
    }

    @Test
    fun `parallel workers run every job exactly once`() {
        val keys = (1..30).map { "k$it" }
        keys.forEach { enqueue("TEST_SLOW", it) }
        val now = later()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)

        val futures = (1..4).map {
            pool.submit<Int> {
                start.await()
                var ran = 0
                while (true) {
                    val claimed = worker.runOnce(now)
                    if (claimed == 0) break
                    ran += claimed
                }
                ran
            }
        }
        start.countDown()
        val total = futures.sumOf { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()

        assertThat(total).isEqualTo(30)
        keys.forEach { assertThat(handler("TEST_SLOW").calls(it)).isEqualTo(1) }
        assertThat(
            jdbc.queryForObject("SELECT count(*) FROM async_jobs WHERE status = 'SUCCEEDED'", Long::class.java)
        ).isEqualTo(30L)
    }

    @Test
    fun `a job whose lease expired is claimed again`() {
        val now = later()
        insertRunning("TEST_OK", "crashed", attempts = 1, leaseUntil = now.minusSeconds(1))
        insertRunning("TEST_OK", "busy", attempts = 1, leaseUntil = now.plusSeconds(300))

        assertThat(worker.runOnce(now)).isEqualTo(1)

        val recovered = row("TEST_OK", "crashed")
        assertThat(recovered.status).isEqualTo("SUCCEEDED")
        assertThat(recovered.attempts).isEqualTo(2)
        assertThat(handler("TEST_OK").calls("crashed")).isEqualTo(1)
        // A job that still holds its lease is left alone.
        assertThat(row("TEST_OK", "busy").status).isEqualTo("RUNNING")
        assertThat(handler("TEST_OK").calls("busy")).isZero()
    }

    @Test
    fun `an expired lease after the last permitted attempt fails without running`() {
        val now = later()
        insertRunning("TEST_OK", "last", attempts = 5, leaseUntil = now.minusSeconds(1))

        assertThat(worker.runOnce(now)).isZero()

        val row = row("TEST_OK", "last")
        assertThat(row.status).isEqualTo("FAILED")
        assertThat(row.lastErrorCode).isEqualTo(AsyncJobWorker.LEASE_EXPIRED)
        assertThat(handler("TEST_OK").calls("last")).isZero()
    }

    @Test
    fun `a late result from an older attempt is ignored`() {
        val now = later()
        insertRunning("TEST_OK", "taken-over", attempts = 2, leaseUntil = now.plusSeconds(300))
        val id = jdbc.queryForObject("SELECT id FROM async_jobs WHERE target_key = 'taken-over'", Long::class.java)!!

        val staleSuccess = transactions.execute { jobs.markSucceeded(id, 1, now) }
        val staleRetry = transactions.execute { jobs.markRetry(id, 1, now, "STALE", now) }
        val staleFailure = transactions.execute { jobs.markFailed(id, 1, "STALE", now) }

        assertThat(listOf(staleSuccess, staleRetry, staleFailure)).containsOnly(0)
        val row = row("TEST_OK", "taken-over")
        assertThat(row.status).isEqualTo("RUNNING")
        assertThat(row.attempts).isEqualTo(2)
        assertThat(row.lastErrorCode).isNull()
    }

    @Test
    fun `enqueue needs the owner's transaction and disappears when it rolls back`() {
        assertThatThrownBy { jobService.enqueue("TEST_OK", "x") }
            .isInstanceOf(IllegalTransactionStateException::class.java)

        assertThatThrownBy {
            transactions.executeWithoutResult {
                jobService.enqueue("TEST_OK", "y")
                throw IllegalStateException("owner change failed")
            }
        }.isInstanceOf(IllegalStateException::class.java)

        assertThat(count()).isZero()
    }

    private fun enqueue(type: String, key: String, delay: Duration = Duration.ZERO): Boolean =
        transactions.execute { jobService.enqueue(type, key, delay) }!!

    // Whole-microsecond time that is later than any enqueue made by the test itself.
    private fun later(): Instant = Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(1)

    private fun count(): Long = jdbc.queryForObject("SELECT count(*) FROM async_jobs", Long::class.java)!!

    private fun handler(type: String): RecordingHandler = recorders.single { it.jobType == type }

    private fun row(type: String, key: String): JobRow = jdbc.queryForObject(
        """SELECT status, attempts, next_attempt_at, lease_until, last_error_code
            FROM async_jobs WHERE job_type = ? AND target_key = ? ORDER BY id DESC LIMIT 1""",
        RowMapper { rs, _ ->
            JobRow(
                status = rs.getString("status"),
                attempts = rs.getInt("attempts"),
                nextAttemptAt = rs.getTimestamp("next_attempt_at").toInstant(),
                leaseUntil = rs.getTimestamp("lease_until")?.toInstant(),
                lastErrorCode = rs.getString("last_error_code")
            )
        },
        type,
        key
    )!!

    private fun dumpRows(): String = jdbc.queryForList("SELECT * FROM async_jobs").toString()

    private fun insertRunning(type: String, key: String, attempts: Int, leaseUntil: Instant) {
        val past = Timestamp.from(leaseUntil.minusSeconds(600))
        jdbc.update(
            """INSERT INTO async_jobs(job_type, target_key, status, attempts, next_attempt_at, lease_until,
                created_at, updated_at)
                VALUES (?, ?, 'RUNNING', ?, ?, ?, ?, ?)""",
            type,
            key,
            attempts,
            past,
            Timestamp.from(leaseUntil),
            past,
            past
        )
    }

    private data class JobRow(
        val status: String,
        val attempts: Int,
        val nextAttemptAt: Instant,
        val leaseUntil: Instant?,
        val lastErrorCode: String?
    )
}

private const val SECRET = "secret-token-123"

class RecordingHandler(
    override val jobType: String,
    private val behavior: (key: String, call: Int) -> Unit
) : AsyncJobHandler {
    private val callCounts = ConcurrentHashMap<String, AtomicInteger>()

    override fun handle(targetKey: String) {
        val call = callCounts.computeIfAbsent(targetKey) { AtomicInteger() }.incrementAndGet()
        behavior(targetKey, call)
    }

    fun calls(key: String): Int = callCounts[key]?.get() ?: 0
}
