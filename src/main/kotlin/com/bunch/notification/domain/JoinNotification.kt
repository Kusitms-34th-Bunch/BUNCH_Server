package com.bunch.notification.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(
    name = "bunch_join_notifications",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_join_notification_recipient",
            columnNames = [
                "joined_membership_id",
                "recipient_member_id"
            ]
        )
    ]
)
class JoinNotification(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val bunchId: Long,

    @Column(nullable = false)
    val joinedMembershipId: Long,

    @Column(nullable = false)
    val recipientMemberId: Long,

    @Column(nullable = false)
    val joinedMemberId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    val type: JoinNotificationType = JoinNotificationType.NEW_MEMBER,

    @Column(nullable = false)
    val createdAt: Instant
)
