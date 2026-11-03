package com.bunch.photo.domain

import com.bunch.bunch.domain.Bunch
import com.bunch.image.domain.ImageAsset
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@Entity
@Table(name = "photos")
class Photo(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bunch_id", nullable = false)
    val bunch: Bunch,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "image_asset_id", nullable = false)
    val imageAsset: ImageAsset,

    @Column(name = "file_hash", nullable = false, length = 64)
    val fileHash: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 16)
    var processingStatus: ProcessingStatus = ProcessingStatus.PENDING,

    @Column(name = "captured_date")
    val capturedDate: LocalDate? = null,

    @Column(name = "captured_time")
    val capturedTime: LocalTime? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "date_source", nullable = false, length = 16)
    val dateSource: DateSource = DateSource.UNKNOWN,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
) {
    init {
        require(SHA256_HEX.matches(fileHash)) {
            "fileHash must be a lowercase SHA-256 hexadecimal value"
        }
        require(capturedTime == null || capturedDate != null) {
            "capturedTime requires capturedDate"
        }
        require(
            when (dateSource) {
                DateSource.UNKNOWN -> capturedDate == null && capturedTime == null
                DateSource.EXIF,
                DateSource.USER -> capturedDate != null
            }
        ) {
            "dateSource and captured date do not match"
        }
    }

    private companion object {
        val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}
