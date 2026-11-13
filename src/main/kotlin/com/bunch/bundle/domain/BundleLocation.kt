package com.bunch.bundle.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "bundle_locations")
class BundleLocation(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "bundle_id", nullable = false)
    val bundleId: Long,

    @Column(nullable = false, length = 32)
    var provider: String,

    @Column(name = "external_id", nullable = false, length = 128)
    var externalId: String,

    @Column(nullable = false, columnDefinition = "text")
    var name: String,

    @Column(columnDefinition = "text")
    var address: String?,

    @Column(nullable = false)
    var position: Int
)
