package com.bunch.recommendation.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

/**
 * A photo of a recommended bundle's group (4.2.9 rule 2). Every photo of the group is kept, also the ones the card
 * does not show and the ones left out as a burst. Only the photos chosen for the card (at most 10) have a [position],
 * the order they are shown in, so a photo is chosen exactly when it has one.
 */
@Entity
@Table(
    name = "candidate_photos",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_candidate_photo_candidate_photo",
            columnNames = ["candidate_id", "photo_id"]
        )
    ],
    indexes = [
        Index(
            name = "ix_candidate_photos_photo_id",
            columnList = "photo_id"
        )
    ]
)
class CandidatePhoto(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "candidate_id", nullable = false)
    val candidateId: Long,

    @Column(name = "photo_id", nullable = false)
    val photoId: Long,

    var position: Int? = null
) {
    init {
        val order = position
        require(order == null || order >= 1) { "position starts at 1" }
    }
}
