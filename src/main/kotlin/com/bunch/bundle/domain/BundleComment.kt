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

@Entity
@Table(
    name = "bundle_comments",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_bundle_comment_bundle_member",
            columnNames = ["bundle_id", "member_id"]
        )
    ],
    indexes = [
        Index(
            name = "ix_bundle_comments_member_id",
            columnList = "member_id"
        )
    ]
)
class BundleComment(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "bundle_id", nullable = false)
    val bundleId: Long,

    @Column(name = "member_id", nullable = false)
    val memberId: Long,

    @Column(nullable = false, columnDefinition = "text")
    var content: String = "",

    @Column(nullable = false)
    var version: Long = 0,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt
)
