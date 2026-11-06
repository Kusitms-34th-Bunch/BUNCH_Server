package com.bunch.photo.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * The image fingerprint of a photo (see `ImageFingerprint`). It is kept apart from the photo so the photo table does
 * not change, and it is deleted with the photo by the database.
 */
@Entity
@Table(name = "photo_image_fingerprints")
class PhotoImageFingerprint(
    @Id
    @Column(name = "photo_id")
    val photoId: Long,

    @Column(nullable = false)
    val fingerprint: Long,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
)
