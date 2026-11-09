package com.bunch.social.domain

import com.bunch.bunch.domain.BunchReportReason
import com.bunch.feed.domain.FeedContentKind
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
@Table(name = "social_content_reports")
class SocialContentReport(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val reporterBunchId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val targetKind: FeedContentKind,

    @Column(nullable = false)
    val targetId: Long,

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
    val windowStartedAt: Instant,

    val reviewedAt: Instant? = null
)
