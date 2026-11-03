package com.bunch.global.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

// Tests switch the worker off and call AsyncJobWorker.runOnce directly.
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    prefix = "async-job.worker",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true
)
class SchedulingConfig
