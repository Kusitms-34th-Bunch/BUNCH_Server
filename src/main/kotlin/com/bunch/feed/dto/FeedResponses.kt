package com.bunch.feed.dto

import com.bunch.bunch.domain.CharacterType
import com.bunch.bunch.domain.Visibility
import com.bunch.bundle.dto.BundleLocationResponse
import com.bunch.bundle.dto.BundleParticipantResponse
import com.bunch.feed.domain.FeedSegment
import java.time.Instant
import java.time.LocalDate

data class FeedResponse(
    val anchorTime: Instant,
    val currentBunchId: Long?,
    val canInteract: Boolean,
    val size: Int,
    val bundleCount: Int,
    val items: List<FeedItem>,
    val nextCursor: String?,
    val hasNext: Boolean,
    val endReached: Boolean
)

sealed interface FeedItem {
    val type: String
}

data class FeedBundleItem(
    val segment: FeedSegment,
    val bundle: FeedBundleCard
) : FeedItem {
    override val type: String = "BUNDLE"
}

data class FeedAdvertisementItem(val advertisement: AdvertisementResponse) : FeedItem {
    override val type: String = "ADVERTISEMENT"
}

data class FeedFollowingCaughtUpItem(override val type: String = "FOLLOWING_CAUGHT_UP") : FeedItem

data class FeedBundleCard(
    val id: Long,
    val bunch: FeedBunchInfo,
    val publishedAt: Instant,
    val title: String,
    val date: LocalDate?,
    val coverImageUrl: String?,
    val photos: List<FeedPhoto>,
    val locations: List<BundleLocationResponse>,
    val participants: List<BundleParticipantResponse>,
    val likeCount: Long,
    val likedByCurrentBunch: Boolean,
    val commentCount: Long
)

data class FeedBunchInfo(
    val id: Long,
    val name: String,
    val imageUrl: String?,
    val characterType: CharacterType,
    val visibility: Visibility
)

data class FeedPhoto(val id: Long, val imageUrl: String, val position: Int)
