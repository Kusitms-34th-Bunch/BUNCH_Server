package com.bunch.bunch.service

import com.bunch.bunch.domain.BunchAccessNoticeRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class BunchAccessNoticeRetention(
    private val notices: BunchAccessNoticeRepository,
    private val clock: Clock
) {
    @Scheduled(fixedDelayString = "\${bunch.access-notice.retention.interval-ms:3600000}")
    @Transactional
    fun deleteExpired(): Int = notices.deleteExpired(clock.instant())
}
