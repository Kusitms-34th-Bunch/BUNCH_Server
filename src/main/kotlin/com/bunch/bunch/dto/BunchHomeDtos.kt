package com.bunch.bunch.dto

import com.bunch.bunch.domain.Bunch
import com.bunch.bunch.domain.BunchAccessScope
import com.bunch.bunch.domain.BunchMembership
import com.bunch.bunch.domain.CharacterType
import com.bunch.bunch.domain.ProfileImageAction
import com.bunch.bunch.domain.Visibility
import java.time.Instant

data class BunchCardResponse(
    val id: Long,
    val name: String,
    val imageUrl: String?,
    val characterType: CharacterType,
    val visibility: Visibility
) {
    companion object {
        fun from(bunch: Bunch) = BunchCardResponse(
            id = bunch.id,
            name = bunch.name,
            imageUrl = bunch.imageUrl,
            characterType = bunch.characterType,
            visibility = bunch.visibility
        )
    }
}

data class BunchHomeProfileResponse(
    val id: Long,
    val name: String,
    val imageUrl: String?,
    val characterType: CharacterType,
    val visibility: Visibility,
    val introduction: String
) {
    companion object {
        fun from(bunch: Bunch) = BunchHomeProfileResponse(
            id = bunch.id,
            name = bunch.name,
            imageUrl = bunch.imageUrl,
            characterType = bunch.characterType,
            visibility = bunch.visibility,
            introduction = bunch.introduction
        )
    }
}

data class BunchPermissions(
    val canEditProfile: Boolean,
    val canDeleteBunch: Boolean,
    val canManageRoles: Boolean,
    val canTransferOwner: Boolean,
    val canBlock: Boolean,
    val canInvite: Boolean,
    val canLeave: Boolean
)

data class MyBunchItem(
    val bunch: BunchCardResponse,
    val myMembership: MyMembershipResponse,
    val unreadCount: Long,
    val lastUsedAt: Instant
)

data class MyBunchesResponse(
    val activeCount: Long,
    val maxActiveCount: Int,
    val defaultBunchId: Long?,
    val activeBunches: List<MyBunchItem>,
    val unavailableBunches: List<BunchAccessNoticeResponse>
)

data class BunchHomeResponse(
    val bunch: BunchHomeProfileResponse,
    val myMembership: MyMembershipResponse?,
    val permissions: BunchPermissions,
    val profileVersion: Long?,
    val membershipVersion: Long?,
    val memberCount: Long,
    val followerCount: Long,
    val followingCount: Long,
    val draftCount: Long?,
    val publishedCount: Long?,
    val stage: Int,
    val unreadCount: Long?,
    val currentBunchId: Long?,
    val accessScope: BunchAccessScope,
    val followState: FollowState,
    val followId: Long?,
    val canViewBundles: Boolean,
    val canViewMembers: Boolean,
    val canViewRelations: Boolean,
    val isCurrentBunch: Boolean
)

data class BunchProfileRequest(
    val name: String,
    val introduction: String,
    val visibility: Visibility,
    val imageAction: ProfileImageAction,
    val expectedProfileVersion: Long
)

data class BunchProfileResponse(
    val bunchId: Long,
    val profileVersion: Long
)

fun BunchMembership.toHomeMembershipResponse() = toMyMembershipResponse()
