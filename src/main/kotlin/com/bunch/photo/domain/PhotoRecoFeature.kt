package com.bunch.photo.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * The features of a photo that the recommendation uses (recommended bundle specification 4-5). It is kept apart from
 * the photo, like the image fingerprint, and the database deletes it with the photo. A photo without a row, or with
 * no [source], has no known original type.
 */
@Entity
@Table(name = "photo_reco_feature")
class PhotoRecoFeature(
    @Id
    @Column(name = "photo_id")
    val photoId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 16)
    val source: PhotoSourceType? = null
)
