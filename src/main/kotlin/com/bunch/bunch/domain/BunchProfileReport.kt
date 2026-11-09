package com.bunch.bunch.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "bunch_profile_reports")
class BunchProfileReport(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val reporterBunchId: Long,

    @Column(nullable = false)
    val targetBunchId: Long,

    @Column(nullable = false)
    val reportedByMemberId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    val reason: BunchReportReason,

    @Column(columnDefinition = "text")
    val detail: String?,

    @Column(nullable = false, length = 16)
    val status: String = "RECEIVED",

    @Column(nullable = false)
    val createdAt: Instant,

    @Column(nullable = false)
    val windowStartedAt: Instant = createdAt,

    val reviewedAt: Instant? = null
)
