package com.bunch.feed.dto

import com.bunch.place.domain.PlaceDetails

data class AdvertisementResponse(
    val id: Long,
    val name: String,
    val introduction: String,
    val photoUrl: String,
    val tags: List<String>,
    val externalLink: String?,
    val place: PlaceDetails,
    val pinned: Boolean,
    val pinId: Long?,
    val canPin: Boolean
)

data class AdvertisementPlacement(
    val afterBundle: Int,
    val advertisement: AdvertisementResponse
)

data class AdvertisementBatchResponse(
    val placements: List<AdvertisementPlacement>,
    val lastAdId: Long?
)
