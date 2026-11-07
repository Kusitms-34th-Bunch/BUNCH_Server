package com.bunch.place.dto

import com.bunch.place.domain.PinnedPlace
import com.bunch.place.domain.PinnedPlaceSource
import java.time.Instant

data class PinnedPlaceResponse(
    val id: Long,
    val bunchId: Long,
    val provider: String,
    val externalId: String,
    val name: String,
    val address: String,
    val sourceType: PinnedPlaceSource,
    val sourceBundleId: Long?,
    val sourceLocationId: Long?,
    val sourceAdvertisementId: Long?,
    val pinnedByMemberId: Long?,
    val pinnedAt: Instant
) {
    companion object {
        fun from(pin: PinnedPlace) = PinnedPlaceResponse(
            pin.id, pin.bunchId, pin.provider, pin.externalId, pin.name, pin.address,
            pin.sourceType, pin.sourceBundleId, pin.sourceLocationId, pin.sourceAdvertisementId,
            pin.pinnedByMemberId, pin.pinnedAt
        )
    }
}

data class BundlePlacePinsResponse(
    val bundleId: Long,
    val currentBunchId: Long?,
    val canPin: Boolean,
    val locations: List<BundlePlacePinResponse>
)

data class BundlePlacePinResponse(
    val id: Long,
    val provider: String,
    val externalId: String,
    val name: String,
    val address: String,
    val position: Int,
    val pinned: Boolean,
    val pinId: Long?
)
