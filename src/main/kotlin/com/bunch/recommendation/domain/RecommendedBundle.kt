package com.bunch.recommendation.domain

import com.bunch.recommendation.domain.combination.ActivityCode
import com.bunch.recommendation.domain.combination.BundleKind
import com.bunch.recommendation.domain.combination.EstablishedBy
import com.bunch.recommendation.domain.combination.EventType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.time.LocalDate

/**
 * The result of the calculation of one bundle of a day (recommended bundle specification 4-5, 10-3): what a bunch is
 * offered. It is not a bundle a member makes (`bundles`), and it is not an old recommended bundle ([BundleCandidate]).
 * A bundle that disappears is deleted, there is no status.
 *
 * When the day is calculated again, a bundle that has the same photos has the same [stableKey] and is updated: its
 * [id], [createdAt] and [visibleFrom] stay (10-3), so the list does not flicker and the bundle is not shown again. What
 * the calculation gives again (the title, the activity, the counts, the trace) is replaced. The cover of the card is
 * the photo with the place 1 ([RecommendedBundlePhoto.cardPosition]).
 *
 * [ruleTrace] is how the bundle was made (11). It is kept to be read and is never calculated from.
 *
 * [lastPhotoAddedAt] is when the last photo of the bundle was added to the photo box (the latest `photos.created_at` of
 * its photos). The bundle is no longer shown 30 days after the day it is (4.2.9 rule 4, [BundleExpiry]). A bundle with
 * the same photos has the same time, so it is not written again.
 */
@Entity
@Table(name = "recommended_bundles")
class RecommendedBundle(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "bunch_id", nullable = false)
    val bunchId: Long,

    @Column(name = "day_key", nullable = false)
    val dayKey: LocalDate,

    @Column(name = "stable_key", nullable = false, length = 64)
    val stableKey: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var kind: BundleKind,

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", length = 32)
    var eventType: EventType?,

    @Enumerated(EnumType.STRING)
    @Column(name = "established_by", length = 16)
    var establishedBy: EstablishedBy?,

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    var activity: ActivityCode?,

    @Column(name = "sub_index")
    var subIndex: Int?,

    @Column(nullable = false, length = 100)
    var title: String,

    @Column(name = "start_at", nullable = false)
    var startAt: Instant,

    @Column(name = "end_at", nullable = false)
    var endAt: Instant,

    @Column(name = "photo_count", nullable = false)
    var photoCount: Int,

    @Column(name = "uploader_count", nullable = false)
    var uploaderCount: Int,

    @Column(name = "has_group_photo", nullable = false)
    var hasGroupPhoto: Boolean,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_trace", nullable = false, columnDefinition = "jsonb")
    var ruleTrace: Map<String, Any?>,

    @Column(name = "visible_from", nullable = false)
    val visibleFrom: Instant,

    @Column(name = "last_photo_added_at", nullable = false)
    val lastPhotoAddedAt: Instant,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt
) {
    init {
        require(title.isNotBlank()) { "title must not be blank" }
        require(!endAt.isBefore(startAt)) { "a bundle does not end before it starts" }
        require((kind == BundleKind.EVENT) == (eventType != null && establishedBy != null)) {
            "an event bundle has an event type and what made it stand, a general bundle has neither"
        }
    }
}
