package com.bunch.feed.domain

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
@Table(name = "feed_content_hides")
class FeedContentHide(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(name = "target_kind", nullable = false, length = 16)
    val targetKind: FeedContentKind,

    @Column(name = "target_id", nullable = false)
    val targetId: Long,

    @Column(name = "reporter_bunch_id")
    val reporterBunchId: Long? = null,

    @Column(nullable = false, columnDefinition = "text")
    val reason: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
)
