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
import java.time.Instant

@Entity
@Table(
    name = "bunch_access_notices",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_bunch_access_notice_recipient_membership_reason",
            columnNames = ["recipient_member_id", "membership_id", "reason"]
        )
    ],
    indexes = [
        Index(
            name = "ix_bunch_access_notice_recipient_occurred",
            columnList = "recipient_member_id, occurred_at, id"
        ),
        Index(
            name = "ix_bunch_access_notice_expires",
            columnList = "expires_at, id"
        )
    ]
)
class BunchAccessNotice(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "recipient_member_id", nullable = false)
    val recipientMemberId: Long,

    @Column(name = "bunch_id", nullable = false)
    val bunchId: Long,

    @Column(name = "membership_id", nullable = false)
    val membershipId: Long,

    @Column(name = "bunch_name", nullable = false, length = 120)
    val bunchName: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "character_type", nullable = false, length = 16)
    val characterType: CharacterType,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val reason: BunchAccessNoticeReason,

    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant,

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant
)
