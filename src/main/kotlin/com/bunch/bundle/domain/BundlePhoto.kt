package com.bunch.bundle.domain

import com.bunch.image.domain.ImageAsset
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(
    name = "bundle_photos",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_bundle_photo_bundle_photo",
            columnNames = ["bundle_id", "photo_id"]
        )
    ],
    indexes = [
        Index(
            name = "ix_bundle_photos_bundle_position_id",
            columnList = "bundle_id, position, id"
        ),
        Index(
            name = "ix_bundle_photos_photo_id",
            columnList = "photo_id"
        )
    ]
)
class BundlePhoto(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "bundle_id", nullable = false)
    val bundleId: Long,

    @Column(name = "photo_id")
    var photoId: Long?,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "image_asset_id", nullable = false)
    val imageAsset: ImageAsset,

    @Column(nullable = false)
    var position: Int,

    @Column(name = "added_by", nullable = false)
    val addedBy: Long,

    @Column(name = "added_at", nullable = false)
    val addedAt: Instant = Instant.now(),

    @Column(nullable = false, columnDefinition = "text")
    var caption: String = "",

    @Column(name = "caption_version", nullable = false)
    var captionVersion: Long = 0,

    @Column(name = "caption_updated_by")
    var captionUpdatedBy: Long? = null,

    @Column(name = "caption_updated_at")
    var captionUpdatedAt: Instant? = null
)
