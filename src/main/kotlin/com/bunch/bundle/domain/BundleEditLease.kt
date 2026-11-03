package com.bunch.bundle.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "bundle_edit_leases",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_bundle_edit_lease_target",
            columnNames = ["bundle_id", "target"]
        )
    ],
    indexes = [
        Index(
            name = "ix_bundle_edit_leases_bundle",
            columnList = "bundle_id"
        ),
        Index(
            name = "ix_bundle_edit_leases_connection",
            columnList = "connection_id"
        )
    ]
)
class BundleEditLease(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "bundle_id", nullable = false)
    val bundleId: Long,

    @Column(nullable = false, length = 64)
    val target: String,

    @Column(name = "member_id", nullable = false)
    var memberId: Long,

    @Column(name = "session_id", nullable = false, length = 36)
    var sessionId: String,

    @Column(name = "connection_id", nullable = false, length = 255)
    var connectionId: String,

    @Column(nullable = false)
    var token: UUID,

    @Column(nullable = false)
    var version: Long,

    @Column(name = "last_heartbeat_at", nullable = false)
    var lastHeartbeatAt: Instant,

    @Column(name = "released_at")
    var releasedAt: Instant? = null
) {
    fun isActive(now: Instant): Boolean =
        releasedAt == null && lastHeartbeatAt.plusSeconds(LEASE_SECONDS).isAfter(now)

    fun isOwnedBy(actorMemberId: Long, actorSessionId: String, actorConnectionId: String? = null): Boolean =
        memberId == actorMemberId &&
            sessionId == actorSessionId &&
            (actorConnectionId == null || connectionId == actorConnectionId)

    fun acquire(
        memberId: Long,
        sessionId: String,
        connectionId: String,
        token: UUID,
        now: Instant
    ) {
        this.memberId = memberId
        this.sessionId = sessionId
        this.connectionId = connectionId
        this.token = token
        this.version++
        this.lastHeartbeatAt = now
        this.releasedAt = null
    }

    fun heartbeat(now: Instant) {
        lastHeartbeatAt = now
    }

    fun release(now: Instant) {
        if (releasedAt == null) {
            version++
            releasedAt = now
        }
    }

    companion object {
        const val LEASE_SECONDS: Long = 15
    }
}
