package com.bunch.bundle.domain

import com.bunch.bunch.domain.Visibility
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate

@Entity
@Table(
    name = "bundles",
    indexes = [
        Index(
            name = "ix_bundles_bunch_status_id",
            columnList = "bunch_id, status, id"
        )
    ]
)
class Bundle(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "bunch_id", nullable = false)
    val bunchId: Long,

    @Column(name = "created_by", nullable = false)
    val createdBy: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: BundleStatus = BundleStatus.DRAFT,

    @Column(nullable = false, columnDefinition = "text")
    var title: String = "",

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    var visibility: Visibility? = null,

    @Column(name = "bundle_date")
    var date: LocalDate? = null,

    @Column(nullable = false)
    var revision: Long = 0,

    @Column(name = "title_version", nullable = false)
    var titleVersion: Long = 0,

    @Column(name = "visibility_version", nullable = false)
    var visibilityVersion: Long = 0,

    @Column(name = "date_version", nullable = false)
    var dateVersion: Long = 0,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt,

    @Column(name = "published_at")
    var publishedAt: Instant? = null
)
