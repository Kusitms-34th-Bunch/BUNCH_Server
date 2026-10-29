package com.bunch.global.job

/**
 * Runs one kind of job. A job may run more than once (lease expiry, lost result),
 * so handle() must be idempotent and must read current data instead of trusting the enqueue time.
 */
interface AsyncJobHandler {
    val jobType: String

    fun handle(targetKey: String)
}
