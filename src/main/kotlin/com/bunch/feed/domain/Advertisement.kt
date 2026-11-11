package com.bunch.feed.domain

import com.bunch.place.domain.PlaceDetails
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(name = "feed_advertisements")
class Advertisement(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, columnDefinition = "text")
    val name: String,

    @Column(nullable = false, columnDefinition = "text")
    val introduction: String,

    @Column(name = "photo_url", nullable = false, columnDefinition = "text")
    val photoUrl: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    val tags: List<String> = emptyList(),

    @Column(name = "external_link", columnDefinition = "text")
    val externalLink: String? = null,

    @Column(name = "place_provider", nullable = false, length = 32)
    val placeProvider: String,

    @Column(name = "place_external_id", nullable = false, length = 128)
    val placeExternalId: String,

    @Column(name = "place_name", nullable = false, columnDefinition = "text")
    val placeName: String,

    @Column(name = "place_address", columnDefinition = "text")
    val placeAddress: String?,

    @Column(nullable = false)
    val active: Boolean = true,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now()
) {
    fun place(): PlaceDetails = PlaceDetails.of(placeProvider, placeExternalId, placeName, placeAddress)
}
