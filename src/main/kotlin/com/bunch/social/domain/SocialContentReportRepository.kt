package com.bunch.social.domain

import com.bunch.feed.domain.FeedContentKind
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface SocialContentReportRepository : JpaRepository<SocialContentReport, Long> {
    fun findFirstByReporterBunchIdAndTargetKindAndTargetIdOrderByWindowStartedAtDescIdDesc(
        reporterBunchId: Long,
        targetKind: FeedContentKind,
        targetId: Long
    ): SocialContentReport?

    fun countByReporterBunchIdAndTargetKindAndTargetIdAndWindowStartedAt(
        reporterBunchId: Long,
        targetKind: FeedContentKind,
        targetId: Long,
        windowStartedAt: Instant
    ): Long
}
