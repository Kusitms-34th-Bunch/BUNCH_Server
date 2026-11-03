package com.bunch.notification.service

import com.bunch.notification.domain.NotificationRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class NotificationRetention(
    private val notifications: NotificationRepository,
    private val clock: Clock
) {
    @Scheduled(fixedDelayString = "\${notification.retention.interval-ms:3600000}")
    @Transactional
    fun deleteExpired(): Int = notifications.deleteExpired(clock.instant().minus(NotificationService.RETENTION))
}
