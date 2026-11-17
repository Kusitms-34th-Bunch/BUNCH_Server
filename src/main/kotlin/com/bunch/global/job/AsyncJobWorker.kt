package com.bunch.global.job

import com.bunch.global.exception.BusinessException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant

@Component
class AsyncJobWorker(
    private val jobs: AsyncJobRepository,
    handlers: List<AsyncJobHandler>,
    transactionManager: PlatformTransactionManager,
    @Value("\${async-job.worker.batch-size:10}")
    private val batchSize: Int,
    @Value("\${async-job.worker.lease-seconds:300}")
    private val leaseSeconds: Long,
    @Value("\${async-job.retry.max-attempts:5}")
    private val maxAttempts: Int,
    @Value("\${async-job.retry.base-seconds:10}")
    private val retryBaseSeconds: Long
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val transactions = TransactionTemplate(transactionManager)
    private val handlersByType: Map<String, AsyncJobHandler> = handlers
        .groupBy { it.jobType }
        .mapValues { (type, sameType) ->
            check(sameType.size == 1) { "Duplicate async job handler: $type" }
            sameType.single()
        }

    // Scheduling is switched on by SchedulingConfig; without it this method is never called.
    @Scheduled(fixedDelayString = "\${async-job.worker.poll-interval-ms:2000}")
    fun poll() {
        try {
            runOnce()
        } catch (ex: Exception) {
            // Never include the exception message: it may carry URLs or credentials.
            logger.warn("Async job polling failed: {}", ex.javaClass.simpleName)
        }
    }

    /** Claims due jobs and runs them. [now] is a parameter so tests can move time without sleeping. */
    fun runOnce(now: Instant = Instant.now()): Int {
        val claimed = claim(now)
        claimed.forEach { job ->
            try {
                execute(job, now)
            } catch (ex: Exception) {
                // The job stays RUNNING and is claimed again after its lease expires.
                logger.warn(
                    "Async job bookkeeping failed: id={}, type={}, error={}",
                    job.id,
                    job.jobType,
                    ex.javaClass.simpleName
                )
            }
        }
        return claimed.size
    }

    private fun claim(now: Instant): List<ClaimedJob> =
        transactions.execute {
            jobs.findDueForClaim(now, batchSize).mapNotNull { job ->
                job.updatedAt = now
                if (job.attempts >= maxAttempts) {
                    // The last permitted attempt lost its lease; do not run it a sixth time.
                    job.status = AsyncJobStatus.FAILED
                    job.leaseUntil = null
                    job.lastErrorCode = LEASE_EXPIRED
                    null
                } else {
                    job.attempts += 1
                    job.status = AsyncJobStatus.RUNNING
                    job.leaseUntil = now.plusSeconds(leaseSeconds)
                    ClaimedJob(job.id, job.jobType, job.targetKey, job.attempts)
                }
            }
        } ?: emptyList()

    private fun execute(job: ClaimedJob, now: Instant) {
        val handler = handlersByType[job.jobType]
        if (handler == null) {
            // A missing handler will not appear by retrying.
            recordFailure(job, UNKNOWN_JOB_TYPE, now, retryable = false)
            return
        }

        try {
            handler.handle(job.targetKey)
        } catch (ex: Exception) {
            recordFailure(job, errorCode(ex), now, retryable = true)
            return
        }

        val updated = transactions.execute { jobs.markSucceeded(job.id, job.attempts, now) }
        if (updated != 1) warnStale(job)
    }

    private fun recordFailure(
        job: ClaimedJob,
        errorCode: String,
        now: Instant,
        retryable: Boolean
    ) {
        val finalAttempt = !retryable || job.attempts >= maxAttempts
        val updated = transactions.execute {
            if (finalAttempt) {
                jobs.markFailed(job.id, job.attempts, errorCode, now)
            } else {
                jobs.markRetry(job.id, job.attempts, now.plus(backoff(job.attempts)), errorCode, now)
            }
        }
        logger.warn(
            "Async job failed: id={}, type={}, attempt={}, error={}, retry={}",
            job.id,
            job.jobType,
            job.attempts,
            errorCode,
            !finalAttempt
        )
        if (updated != 1) warnStale(job)
    }

    private fun warnStale(job: ClaimedJob) {
        logger.warn("Async job result ignored, attempt is no longer current: id={}, type={}", job.id, job.jobType)
    }

    // base * 2^(attempt - 1), capped so a long outage does not push retries out of reach.
    private fun backoff(attempts: Int): Duration {
        val exponent = (attempts - 1).coerceIn(0, MAX_EXPONENT)
        val seconds = (retryBaseSeconds shl exponent).coerceAtMost(MAX_BACKOFF_SECONDS)
        return Duration.ofSeconds(seconds)
    }

    // Only the code or the class name is stored; messages can contain secrets.
    private fun errorCode(ex: Exception): String =
        ((ex as? BusinessException)?.errorCode?.code ?: ex.javaClass.simpleName).take(MAX_ERROR_CODE_LENGTH)

    private data class ClaimedJob(
        val id: Long,
        val jobType: String,
        val targetKey: String,
        val attempts: Int
    )

    companion object {
        const val UNKNOWN_JOB_TYPE = "UNKNOWN_JOB_TYPE"
        const val LEASE_EXPIRED = "LEASE_EXPIRED"
        private const val MAX_EXPONENT = 20
        private const val MAX_BACKOFF_SECONDS = 3600L
        private const val MAX_ERROR_CODE_LENGTH = 64
    }
}
