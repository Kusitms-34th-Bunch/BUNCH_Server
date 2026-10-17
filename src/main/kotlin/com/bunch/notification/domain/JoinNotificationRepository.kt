package com.bunch.notification.domain

import org.springframework.data.jpa.repository.JpaRepository

interface JoinNotificationRepository : JpaRepository<JoinNotification, Long>
