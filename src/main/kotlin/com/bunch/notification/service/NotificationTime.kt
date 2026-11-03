package com.bunch.notification.service

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class NotificationTime {
    @Bean
    fun notificationClock(): Clock = Clock.systemUTC()
}
