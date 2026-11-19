package com.bunch.recommendation.domain

import com.bunch.recommendation.domain.combination.PhotoRole
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * A photo of a recommended bundle (recommended bundle specification 4-5, 8-6, 7-10). Every photo of the bundle is kept,
 * also the ones the card does not show. Only the photos chosen for the card have a [cardPosition], from 1, in the order
 * they are shown in, and the one with the place 1 is the cover. A photo is in one recommended bundle only.
 */
@Entity
@Table(name = "recommended_bundle_photos")
class RecommendedBundlePhoto(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "bundle_id", nullable = false)
    val bundleId: Long,

    @Column(name = "photo_id", nullable = false)
    val photoId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    var role: PhotoRole,

    @Column(name = "card_position")
    var cardPosition: Int? = null
) {
    init {
        require(cardPosition == null || cardPosition!! >= 1) { "the place on the card starts at 1" }
    }
}
