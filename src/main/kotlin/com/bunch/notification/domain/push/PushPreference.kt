package com.bunch.notification.domain.push

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(name = "push_preferences")
class PushPreference(
    @Id
    @Column(name = "member_id")
    val memberId: Long,

    @Column(nullable = false)
    var enabled: Boolean = true,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    var types: Map<String, Boolean> = emptyMap(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant
)
