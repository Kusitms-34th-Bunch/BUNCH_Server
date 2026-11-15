package com.bunch.kok.dto

import com.bunch.kok.domain.Kok
import com.bunch.kok.domain.KokSource
import java.time.Instant

data class KokResponse(
    val id: Long,
    val bunchId: Long,
    val provider: String,
    val externalId: String,
    val name: String,
    val address: String?,
    val sourceType: KokSource,
    val sourceBundleId: Long?,
    val sourceLocationId: Long?,
    val sourceAdvertisementId: Long?,
    val pinnedByMemberId: Long?,
    val pinnedAt: Instant
) {
    companion object {
        fun from(pin: Kok) = KokResponse(
            pin.id, pin.bunchId, pin.provider, pin.externalId, pin.name, pin.address,
            pin.sourceType, pin.sourceBundleId, pin.sourceLocationId, pin.sourceAdvertisementId,
            pin.pinnedByMemberId, pin.pinnedAt
        )
    }
}

data class BundleKoksResponse(
    val bundleId: Long,
    val currentBunchId: Long?,
    val canPin: Boolean,
    val locations: List<BundleLocationKokResponse>
)

data class BundleLocationKokResponse(
    val id: Long,
    val provider: String,
    val externalId: String,
    val name: String,
    val address: String?,
    val position: Int,
    val pinned: Boolean,
    val pinId: Long?
)


data class KoksResponse(
    val bunchId: Long,
    val selectedMemberId: Long?,
    val totalCount: Long,
    val members: List<KokMemberResponse>,
    val items: List<KokCardResponse>,
    val nextCursor: String?
)

data class KokMemberResponse(
    val membershipId: Long,
    val memberId: Long,
    val nickname: String?,
    val imageUrl: String?,
    val count: Long
)

data class KokCardResponse(
    val id: Long,
    val provider: String,
    val externalId: String,
    val name: String,
    val address: String?,
    val pinnedByMemberId: Long?,
    val pinnedAt: Instant,
    val sourceType: KokSource,
    val sourceAccessible: Boolean,
    val sourceBundleId: Long?,
    val sourceBunchId: Long?,
    val sourceBunchName: String?,
    val sourceBundleTitle: String?
)

data class KokCursor(
    val version: Int = 1,
    val userId: Long,
    val bunchId: Long,
    val memberId: Long?,
    val pinnedAt: Instant,
    val id: Long
)
