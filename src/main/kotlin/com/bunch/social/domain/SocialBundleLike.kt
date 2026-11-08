package com.bunch.social.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "social_bundle_likes")
class SocialBundleLike(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val bunchId: Long,

    @Column(nullable = false)
    val bundleId: Long,

    @Column(nullable = false)
    var active: Boolean = true,

    @Column(nullable = false)
    val firstLikedAt: Instant,

    @Column(nullable = false)
    var updatedAt: Instant = firstLikedAt
)
