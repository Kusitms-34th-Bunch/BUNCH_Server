package com.bunch.kok.domain

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
@Table(name = "pinned_places")
class Kok(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "bunch_id", nullable = false)
    val bunchId: Long,

    @Column(nullable = false, length = 32)
    val provider: String,

    @Column(name = "external_id", nullable = false, length = 128)
    val externalId: String,

    @Column(nullable = false, columnDefinition = "text")
    val name: String,

    @Column(nullable = false, columnDefinition = "text")
    val address: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 16)
    val sourceType: KokSource,

    @Column(name = "source_bundle_id")
    val sourceBundleId: Long? = null,

    @Column(name = "source_location_id")
    val sourceLocationId: Long? = null,

    @Column(name = "source_advertisement_id")
    val sourceAdvertisementId: Long? = null,

    @Column(name = "pinned_by_member_id")
    val pinnedByMemberId: Long?,

    @Column(name = "pinned_at", nullable = false)
    val pinnedAt: Instant
)
