package com.bunch.bunch.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "bunch_action_requests",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_bunch_action_request_key",
            columnNames = ["member_id", "bunch_id", "operation", "request_key"]
        )
    ],
    indexes = [
        Index(
            name = "ix_bunch_action_requests_expires",
            columnList = "expires_at,id"
        )
    ]
)
class BunchActionRequest(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "member_id", nullable = false)
    val memberId: Long,

    @Column(name = "bunch_id", nullable = false)
    val bunchId: Long,

    @Column(name = "actor_membership_id", nullable = false)
    val actorMembershipId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    val operation: BunchActionOperation,

    @Column(name = "request_key", nullable = false)
    val requestKey: UUID,

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 64)
    val fingerprint: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    val result: Map<String, Any?>,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant
)
