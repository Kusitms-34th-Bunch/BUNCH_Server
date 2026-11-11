package com.bunch.notification.domain.push

import org.springframework.data.jpa.repository.JpaRepository

interface PushPreferenceRepository : JpaRepository<PushPreference, Long>
