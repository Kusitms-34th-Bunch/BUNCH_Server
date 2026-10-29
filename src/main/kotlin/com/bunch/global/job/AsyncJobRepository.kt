package com.bunch.global.job

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface AsyncJobRepository : JpaRepository<AsyncJob, Long> {
    // SKIP LOCKED lets several workers claim different jobs instead of waiting for each other.
    @Query(
        value = """SELECT * FROM async_jobs
        WHERE (status = 'PENDING' AND next_attempt_at <= :now)
        OR (status = 'RUNNING' AND lease_until <= :now)
        ORDER BY next_attempt_at, id
        LIMIT :limit
        FOR UPDATE SKIP LOCKED""",
        nativeQuery = true
    )
    fun findDueForClaim(
        @Param("now") now: Instant,
        @Param("limit") limit: Int
    ): List<AsyncJob>

    // The partial unique index keeps one unfinished job per type and target.
    @Modifying
    @Query(
        value = """INSERT INTO async_jobs(job_type, target_key, status, attempts, next_attempt_at,
        created_at, updated_at)
        VALUES (:jobType, :targetKey, 'PENDING', 0, :runAt, :now, :now)
        ON CONFLICT (job_type, target_key) WHERE status IN ('PENDING', 'RUNNING') DO NOTHING""",
        nativeQuery = true
    )
    fun insertIfNoActive(
        @Param("jobType") jobType: String,
        @Param("targetKey") targetKey: String,
        @Param("runAt") runAt: Instant,
        @Param("now") now: Instant
    ): Int

    // The attempts guard ignores a late result from an attempt whose lease was already taken over.
    @Modifying
    @Query(
        value = """UPDATE async_jobs
        SET status = 'SUCCEEDED', lease_until = NULL, updated_at = :now
        WHERE id = :id AND status = 'RUNNING' AND attempts = :attempts""",
        nativeQuery = true
    )
    fun markSucceeded(
        @Param("id") id: Long,
        @Param("attempts") attempts: Int,
        @Param("now") now: Instant
    ): Int

    @Modifying
    @Query(
        value = """UPDATE async_jobs
        SET status = 'PENDING', lease_until = NULL, next_attempt_at = :nextAttemptAt,
        last_error_code = :errorCode, updated_at = :now
        WHERE id = :id AND status = 'RUNNING' AND attempts = :attempts""",
        nativeQuery = true
    )
    fun markRetry(
        @Param("id") id: Long,
        @Param("attempts") attempts: Int,
        @Param("nextAttemptAt") nextAttemptAt: Instant,
        @Param("errorCode") errorCode: String,
        @Param("now") now: Instant
    ): Int

    @Modifying
    @Query(
        value = """UPDATE async_jobs
        SET status = 'FAILED', lease_until = NULL, last_error_code = :errorCode, updated_at = :now
        WHERE id = :id AND status = 'RUNNING' AND attempts = :attempts""",
        nativeQuery = true
    )
    fun markFailed(
        @Param("id") id: Long,
        @Param("attempts") attempts: Int,
        @Param("errorCode") errorCode: String,
        @Param("now") now: Instant
    ): Int
}
