package com.bunch.global.job

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

@Service
class AsyncJobService(
    private val jobs: AsyncJobRepository
) {
    /**
     * Registers a job in the caller's transaction, so the job exists exactly when the owner's change commits.
     * Returns false when an unfinished job of the same type and target already exists.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun enqueue(
        jobType: String,
        targetKey: String,
        delay: Duration = Duration.ZERO
    ): Boolean {
        require(jobType.isNotBlank() && jobType.length <= MAX_TYPE_LENGTH)
        require(targetKey.isNotBlank() && targetKey.length <= MAX_KEY_LENGTH)
        require(!delay.isNegative)

        val now = Instant.now()
        return jobs.insertIfNoActive(jobType, targetKey, now.plus(delay), now) == 1
    }

    private companion object {
        const val MAX_TYPE_LENGTH = 64
        const val MAX_KEY_LENGTH = 255
    }
}
