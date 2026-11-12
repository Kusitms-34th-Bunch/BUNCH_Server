package com.bunch.bundle.dto

import com.bunch.bunch.domain.MembershipRole
import com.bunch.bunch.domain.Visibility
import com.bunch.bundle.domain.BundleStatus
import com.fasterxml.jackson.annotation.JsonInclude
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
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val picks: BundlePhotoPicks? = null
)

data class BundleParticipantResponse(
    val memberId: Long,
    val joinedAt: Instant,
    val nickname: String? = null,
    val imageUrl: String? = null,
    val profileAvailable: Boolean = false,
    val role: MembershipRole? = null,
    val creator: Boolean = false
)

data class BundleCommentResponse(
    val memberId: Long,
    val content: String,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val nickname: String? = null,
    val imageUrl: String? = null,
    val profileAvailable: Boolean = false,
    val id: Long = 0
)

data class BundleLocationResponse(
    val id: Long,
    val provider: String,
    val externalId: String,
    val name: String,
    val address: String?,
    val position: Int
)

data class BundleLocationState(
    val bundleId: Long,
    val revision: Long,
    val locationVersion: Long,
    val locations: List<BundleLocationResponse>
)

data class BundleLocationChange(
    val changed: Boolean,
    val locations: List<BundleLocationResponse>
)

data class BundlePermissions(
    val administrator: Boolean,
    val creator: Boolean,
    val participant: Boolean,
    val canEdit: Boolean,
    val canPublish: Boolean,
    val canDelete: Boolean,
    val canReport: Boolean = false,
    val canLike: Boolean = false,
    val canComment: Boolean = false
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
    val publishedAt: Instant?,
    val locationVersion: Long,
    val locations: List<BundleLocationResponse>,
    val likeCount: Long = 0,
    val likedByCurrentBunch: Boolean = false,
    val commentCount: Long = 0,
    val publishedBy: Long? = null,
    val publisherNickname: String? = null,
    val titleCrdtState: ByteArray? = null,
    val titleTooLong: Boolean = false,
    val photoChangeResult: BundlePhotoChangeResult? = null
)

data class BundlePage(val items: List<BundleSnapshot>, val page: Int, val size: Int, val total: Long)

data class BundleSummary(val draftCount: Long, val publishedCount: Long, val stage: Int)


data class BundleAttribution(
    val participants: List<BundleParticipantResponse>,
    val comments: List<BundleCommentResponse>
)

data class BundlePhotoChangeResult(
    val addedPhotoIds: List<Long>,
    val rejectedPhotoIds: List<Long>,
    val replayed: Boolean = false
)


data class BundlePhotoPicks(
    val count: Int = 0,
    val pickedByMe: Boolean = false,
    val version: Long = 0,
    val members: List<BundleParticipantResponse> = emptyList()
)

data class BundleTitleState(
    val title: String,
    val titleCrdtState: ByteArray,
    val titleVersion: Long,
    val titleTooLong: Boolean
)

data class TitleMergeResult(val state: ByteArray, val value: String, val tooLong: Boolean)

data class TitleDocument(val value: String, val tooLong: Boolean)

data class BundleLocationIntentResponse(
    val bundleId: Long,
    val revision: Long,
    val locationVersion: Long,
    val changed: Boolean,
    val acceptedCount: Int,
    val locations: List<BundleLocationResponse>
)
