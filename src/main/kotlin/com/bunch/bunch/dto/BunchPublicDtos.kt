package com.bunch.bunch.dto

import com.bunch.bunch.domain.Visibility
import com.bunch.bundle.dto.BundleLocationResponse
import com.bunch.bundle.dto.BundlePhotoPicks
import com.bunch.bundle.dto.BundlePermissions
import java.time.Instant
import java.time.LocalDate

// Legacy single-location field remains null. Typed locations carry the actual ordered places.
data class BunchPublicLocation(val name: String, val latitude: Double, val longitude: Double)

data class BunchPublicBundleCard(
    val id: Long,
    val bunchId: Long,
    val title: String,
    val coverImageUrl: String?,
    val date: LocalDate?,
    val location: BunchPublicLocation? = null,
    val publishedAt: Instant?,
    val locations: List<BundleLocationResponse> = emptyList(),
    val likeCount: Long = 0,
    val likedByCurrentBunch: Boolean = false,
    val commentCount: Long = 0
)

data class BunchPublicBundlePhoto @JvmOverloads constructor(
    val id: Long,
    val imageUrl: String,
    val position: Int,
    val picks: BundlePhotoPicks? = null
)

data class BunchPublicBundleContributor(
    val memberId: Long,
    val nickname: String?,
    val imageUrl: String?,
    val profileAvailable: Boolean = false
)

data class BunchPublicBundleComment(
    val id: Long,
    val memberId: Long,
    val nickname: String?,
    val imageUrl: String?,
    val content: String?,
    val updatedAt: Instant,
    val profileAvailable: Boolean = false
)

data class BunchPublicBundleDetail(
    val id: Long,
    val bunchId: Long,
    val title: String,
    val coverImageUrl: String?,
    val date: LocalDate?,
    val location: BunchPublicLocation? = null,
    val publishedAt: Instant?,
    val photos: List<BunchPublicBundlePhoto>,
    val contributors: List<BunchPublicBundleContributor>,
    val comments: List<BunchPublicBundleComment>,
    val locations: List<BundleLocationResponse>,
    val revision: Long,
    val visibility: Visibility?,
    val permissions: BundlePermissions,
    val likeCount: Long,
    val likedByCurrentBunch: Boolean,
    val commentCount: Long
)

data class BunchPublicBundleCover(val bundleId: Long, val imageUrl: String)
