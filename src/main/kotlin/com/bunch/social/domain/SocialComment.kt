package com.bunch.social.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "social_comments")
class SocialComment(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val bundleId: Long,

    @Column(nullable = false)
    val authorMemberId: Long,

    val actingBunchId: Long?,

    @Column(nullable = false, length = 120)
    val badgeName: String,

    @Column(nullable = false, length = 16)
    val badgeCharacterType: String,

    val parentId: Long? = null,

    @Column(nullable = false, columnDefinition = "text")
    var content: String,

    @Column(nullable = false)
    val requestKey: UUID,

    @Column(nullable = false, length = 64)
    val fingerprint: String,

    @Column(nullable = false)
    val createdAt: Instant,

    var deletedAt: Instant? = null
)
