package com.bunch.bunch.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "bunch_invitations")
class Invitation(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val bunchId: Long,

    @Column(nullable = false)
    val issuerMemberId: Long,

    @Column(nullable = false, length = 43, unique = true)
    val token: String,

    @Column(nullable = false, length = 8, unique = true)
    val code: String,

    @Column(nullable = false)
    val createdAt: Instant,

    @Column(nullable = false)
    val expiresAt: Instant,

    var revokedAt: Instant? = null
) {
    fun isValid(now: Instant): Boolean = revokedAt == null && expiresAt.isAfter(now)
}
