package com.bunch.bunch.domain

import org.springframework.data.jpa.repository.JpaRepository

interface BunchProfileReportRepository : JpaRepository<BunchProfileReport, Long> {
    fun findByReporterBunchIdAndTargetBunchId(reporterBunchId: Long, targetBunchId: Long): BunchProfileReport?
}
