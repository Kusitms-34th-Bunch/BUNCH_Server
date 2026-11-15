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
    name = "bundle_participants",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_bundle_participant_bundle_member",
            columnNames = ["bundle_id", "member_id"]
        )
    ],
    indexes = [
        Index(
            name = "ix_bundle_participants_member_id",
            columnList = "member_id"
        )
    ]
)
class BundleParticipant(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "bundle_id", nullable = false)
    val bundleId: Long,

    @Column(name = "member_id", nullable = false)
    val memberId: Long,

    @Column(name = "joined_at", nullable = false)
    val joinedAt: Instant = Instant.now(),

    @Column(name = "reminder24_notified_at")
    var reminder24SentAt: Instant? = null,

    @Column(name = "reminder72_notified_at")
    var reminder72SentAt: Instant? = null,

    @Column(name = "reminders_cancelled_at")
    var remindersCancelledAt: Instant? = null,

    @Column(name = "pick_initial_version", nullable = false)
    val pickInitialVersion: Long = 0
)
