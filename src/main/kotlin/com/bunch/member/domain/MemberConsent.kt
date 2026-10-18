package com.bunch.member.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "member_consents")
class MemberConsent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val memberId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    val type: TermType,

    @Column(nullable = false, length = 100)
    val version: String,

    @Column(nullable = false)
    val agreedAt: Instant = Instant.now()
)
