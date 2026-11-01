package com.bunch.bunch.dto

import com.bunch.bunch.domain.Bunch
import com.bunch.bunch.domain.CharacterType
import com.bunch.bunch.domain.MembershipRole
import com.bunch.bunch.domain.Visibility
import java.time.Instant
import java.time.LocalDate

data class BunchPublicProfileCard(
    val id: Long,
    val name: String,
    val introduction: String,
    val imageUrl: String?,
    val characterType: CharacterType,
    val visibility: Visibility
) {
    companion object {
        fun from(bunch: Bunch) = BunchPublicProfileCard(
            id = bunch.id,
            name = bunch.name,
            introduction = bunch.introduction,
            imageUrl = bunch.imageUrl,
            characterType = bunch.characterType,
            visibility = bunch.visibility
        )
    }
}

data class BunchPublicProfile(
    val bunch: BunchPublicProfileCard,
    val stage: Int,
    val followerCount: Long,
    val followingCount: Long,
    val memberCount: Long,
    val followState: FollowState,
    val followId: Long?,
    val canViewBundles: Boolean,
    val canViewMembers: Boolean,
    val canViewRelations: Boolean,
    val isCurrentBunch: Boolean
)

data class BunchPublicMembersResponse(val items: List<BunchPublicMember>)

data class BunchPublicMember(
    val memberId: Long,
    val nickname: String?,
    val imageUrl: String?,
    val role: MembershipRole
)

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
