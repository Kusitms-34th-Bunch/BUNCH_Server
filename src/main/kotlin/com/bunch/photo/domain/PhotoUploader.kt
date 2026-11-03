package com.bunch.photo.domain

import com.bunch.member.domain.Member
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
import java.util.UUID

@Entity
@Table(
    name = "photo_uploaders",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_photo_uploader",
            columnNames = ["photo_id", "member_id"]
        )
    ],
    indexes = [
        Index(name = "ix_photo_uploaders_member_id", columnList = "member_id"),
        Index(name = "ix_photo_uploaders_member_uploaded_at", columnList = "member_id, uploaded_at")
    ]
)
class PhotoUploader(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "photo_id", nullable = false)
    val photo: Photo,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    val member: Member,

    @Column(name = "upload_batch_id", nullable = false)
    val uploadBatchId: UUID,

    @Column(name = "uploaded_at", nullable = false)
    val uploadedAt: Instant = Instant.now()
)
