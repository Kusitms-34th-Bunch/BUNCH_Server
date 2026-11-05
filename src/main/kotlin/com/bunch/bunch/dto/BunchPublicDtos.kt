package com.bunch.bunch.dto

import java.time.Instant
import java.time.LocalDate

// The wire contract allows an object here. Bundle has no stored location yet, so values remain null.
data class BunchPublicLocation(val name: String, val latitude: Double, val longitude: Double)

data class BunchPublicBundleCard(
    val id: Long,
    val bunchId: Long,
    val title: String,
    val coverImageUrl: String?,
    val date: LocalDate?,
    // Bundle has no persisted location storage; the current wire contract is explicitly null.
    val location: BunchPublicLocation? = null,
    val publishedAt: Instant?
)

data class BunchPublicBundlePhoto(
    val id: Long,
    val imageUrl: String,
    val position: Int,
    val caption: String
)

data class BunchPublicBundleContributor(
    val memberId: Long,
    val nickname: String?,
    val imageUrl: String?
)

data class BunchPublicBundleComment(
    val id: Long,
    val memberId: Long,
    val nickname: String?,
    val imageUrl: String?,
    val content: String?,
    val updatedAt: Instant
)

data class BunchPublicBundleDetail(
    val id: Long,
    val bunchId: Long,
    val title: String,
    val coverImageUrl: String?,
    val date: LocalDate?,
    // Bundle has no persisted location storage; the current wire contract is explicitly null.
    val location: BunchPublicLocation? = null,
    val publishedAt: Instant?,
    val photos: List<BunchPublicBundlePhoto>,
    val contributors: List<BunchPublicBundleContributor>,
    val comments: List<BunchPublicBundleComment>
)

data class BunchPublicBundleCover(val bundleId: Long, val imageUrl: String)
