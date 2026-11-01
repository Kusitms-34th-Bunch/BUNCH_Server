package com.bunch.notification.domain

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface PushPreferenceRepository : JpaRepository<PushPreference, Long> {
    @Modifying
    @Query(
        value = """
            INSERT INTO push_preferences(member_id, enabled, types, updated_at)
            VALUES (:memberId, COALESCE(CAST(:enabled AS boolean), TRUE), CAST(:types AS jsonb), :updatedAt)
            ON CONFLICT (member_id) DO UPDATE SET
                enabled = COALESCE(CAST(:enabled AS boolean), push_preferences.enabled),
                types = push_preferences.types || CAST(:types AS jsonb),
                updated_at = EXCLUDED.updated_at
        """,
        nativeQuery = true
    )
    fun upsert(
        memberId: Long,
        enabled: Boolean?,
        types: String,
        updatedAt: Instant
    ): Int
}
