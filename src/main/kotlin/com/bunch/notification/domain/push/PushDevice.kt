package com.bunch.notification.domain.push

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "push_devices",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_push_devices_installation_id",
            columnNames = ["installation_id"]
        )
    ]
)
class PushDevice(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "installation_id", nullable = false)
    val installationId: UUID,

    @Column(name = "member_id", nullable = false)
    var memberId: Long,

    @Column(name = "session_id", nullable = false)
    var sessionId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var environment: PushDeviceEnvironment,

    @Column(nullable = false, length = 120)
    var topic: String,

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "token_ciphertext", nullable = false, columnDefinition = "bytea")
    var tokenCiphertext: ByteArray,

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", nullable = false, columnDefinition = "char(64)")
    var tokenHash: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "authorization_status", nullable = false, length = 20)
    var authorizationStatus: PushAuthorizationStatus,

    @Column(name = "registered_at", nullable = false)
    var registeredAt: Instant
)
