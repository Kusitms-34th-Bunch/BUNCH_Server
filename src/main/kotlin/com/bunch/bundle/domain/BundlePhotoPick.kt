package com.bunch.bundle.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "bundle_photo_picks")
class BundlePhotoPick(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "bundle_id", nullable = false)
    val bundleId: Long,

    @Column(name = "bundle_photo_id", nullable = false)
    val bundlePhotoId: Long,

    @Column(name = "member_id", nullable = false)
    val memberId: Long,

    @Column(nullable = false)
    var picked: Boolean,

    @Column(nullable = false)
    var version: Long
)
