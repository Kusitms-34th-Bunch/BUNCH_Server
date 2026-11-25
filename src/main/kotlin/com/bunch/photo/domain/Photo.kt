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
import java.util.UUID

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
    val createdAt: Instant = Instant.now(),

    @Column(name = "date_batch_id")
    val dateBatchId: UUID? = null,

    @Column(name = "original_file_hash", length = 64)
    val originalFileHash: String? = null,

    @Column(name = "captured_utc_offset", length = 6)
    val capturedUtcOffset: String? = null,

    @Column(name = "captured_millis")
    val capturedMillis: Int? = null,

    // The size of the stored image in pixels, the resolution that the duplicate check of the recommendation compares
    // (F7, R1). A photo stored before the columns existed has none.
    @Column(name = "width")
    val width: Int? = null,

    @Column(name = "height")
    val height: Int? = null
) {
    init {
        require((width == null && height == null) || ((width ?: 0) > 0 && (height ?: 0) > 0)) {
            "photo dimensions must both be null or positive"
        }
        require(isSha256Hex(fileHash)) {
            "fileHash must be a lowercase SHA-256 hexadecimal value"
        }
        require(originalFileHash.let { it == null || isSha256Hex(it) }) {
            "originalFileHash must be a lowercase SHA-256 hexadecimal value"
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
        require(dateBatchId == null || dateSource == DateSource.USER) {
            "only a photo the user dated has a date batch"
        }
        require(capturedUtcOffset.let { it == null || UtcOffset.isValid(it) }) {
            "capturedUtcOffset must be a UTC offset from -12:00 to +14:00"
        }
        require(capturedMillis == null || (capturedMillis in 0..MAX_MILLIS && capturedTime != null)) {
            "capturedMillis must be from 0 to 999 and needs a capturedTime"
        }
    }

    companion object {
        private val SHA256_HEX = Regex("[0-9a-f]{64}")
        const val MAX_MILLIS = 999

        /** Whether the value is a SHA-256 in lowercase hexadecimal, the form of every photo hash. */
        fun isSha256Hex(value: String): Boolean = SHA256_HEX.matches(value)
    }
}
