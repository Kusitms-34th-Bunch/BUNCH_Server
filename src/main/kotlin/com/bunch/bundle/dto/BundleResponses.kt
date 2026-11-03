package com.bunch.bundle.dto

import com.bunch.bunch.domain.Visibility
import com.bunch.bundle.domain.BundleStatus
import java.time.Instant
import java.time.LocalDate

data class BundlePhotoResponse(
    val id: Long,
    val photoId: Long?,
    val imageAssetId: Long,
    val imageUrl: String,
    val position: Int,
    val addedBy: Long,
    val addedAt: Instant,
    val caption: String,
    val captionVersion: Long,
    val captionUpdatedBy: Long?,
    val captionUpdatedAt: Instant?
)

data class BundleParticipantResponse(val memberId: Long, val joinedAt: Instant)

data class BundleCommentResponse(
    val memberId: Long,
    val content: String,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class BundlePermissions(
    val administrator: Boolean,
    val creator: Boolean,
    val participant: Boolean,
    val canEdit: Boolean,
    val canPublish: Boolean,
    val canDelete: Boolean
)

data class BundleSnapshot(
    val id: Long,
    val bunchId: Long,
    val createdBy: Long,
    val status: BundleStatus,
    val title: String,
    val titleVersion: Long,
    val visibility: Visibility?,
    val visibilityVersion: Long,
    val date: LocalDate?,
    val dateVersion: Long,
    val revision: Long,
    val photos: List<BundlePhotoResponse>,
    val participants: List<BundleParticipantResponse>,
    val comments: List<BundleCommentResponse>,
    val dateChips: List<LocalDate>,
    val publishBlockers: List<String>,
    val permissions: BundlePermissions,
    val leases: List<LeaseState>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val publishedAt: Instant?
)

data class BundlePage(val items: List<BundleSnapshot>, val page: Int, val size: Int, val total: Long)

data class BundleSummary(val draftCount: Long, val publishedCount: Long, val stage: Int)
