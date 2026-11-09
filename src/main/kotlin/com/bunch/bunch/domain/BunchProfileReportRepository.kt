package com.bunch.bunch.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface BunchProfileReportRepository : JpaRepository<BunchProfileReport, Long> {
    fun findFirstByReporterBunchIdAndTargetBunchIdOrderByWindowStartedAtDescIdDesc(
        reporterBunchId: Long,
        targetBunchId: Long
    ): BunchProfileReport?

    fun countByReporterBunchIdAndTargetBunchIdAndWindowStartedAt(
        reporterBunchId: Long,
        targetBunchId: Long,
        windowStartedAt: Instant
    ): Long
}
