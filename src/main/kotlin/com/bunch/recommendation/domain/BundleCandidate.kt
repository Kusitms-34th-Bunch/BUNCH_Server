package com.bunch.recommendation.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate

/**
 * A recommended bundle: one group of photos taken together (4.2.7) that a bunch is offered. [title] and
 * [titleSentenceKey] are made once and never change while the candidate lives (4.2.2 rule 3-⑤). A candidate that
 * disappears is deleted, there is no status. [endDate] is the next day for a group continued over midnight.
 * [lastPhotoAddedAt] moves only when a new photo comes in and never goes back (4.2.9 rule 3).
 */
@Entity
@Table(
    name = "bundle_candidates",
    indexes = [
        Index(
            name = "ix_bundle_candidates_bunch_start_id",
            columnList = "bunch_id, start_date, id"
        )
    ]
)
class BundleCandidate(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "bunch_id", nullable = false)
    val bunchId: Long,

    @Column(name = "start_date", nullable = false)
    var startDate: LocalDate,

    @Column(name = "end_date")
    var endDate: LocalDate? = null,

    @Column(nullable = false, length = 60)
    val title: String,

    @Column(name = "title_sentence_key", length = 64)
    val titleSentenceKey: String?,

    @Column(name = "last_photo_added_at", nullable = false)
    var lastPhotoAddedAt: Instant,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt
) {
    init {
        require(title.isNotBlank()) { "title must not be blank" }
        val end = endDate
        require(end == null || end.isAfter(startDate)) { "endDate must be after startDate" }
    }
}
