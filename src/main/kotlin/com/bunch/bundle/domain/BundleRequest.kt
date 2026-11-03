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
    name = "bundle_requests",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_bundle_request_key",
            columnNames = ["bunch_id", "member_id", "operation", "request_key"]
        )
    ],
    indexes = [
        Index(
            name = "ix_bundle_requests_bundle_id",
            columnList = "bundle_id"
        )
    ]
)
class BundleRequest(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "bunch_id", nullable = false)
    val bunchId: Long,

    @Column(name = "member_id", nullable = false)
    val memberId: Long,

    @Column(nullable = false, length = 128)
    val operation: String,

    @Column(name = "request_key", nullable = false)
    val requestKey: UUID,

    @Column(nullable = false, length = 64)
    val fingerprint: String,

    // Deliberately a scalar: the idempotency result must survive bundle deletion.
    @Column(name = "bundle_id", nullable = false)
    val bundleId: Long,

    @Column(nullable = false)
    val revision: Long,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
)
