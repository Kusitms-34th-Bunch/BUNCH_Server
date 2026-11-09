package com.bunch.member.dto

import com.bunch.bunch.dto.BunchResponse
import com.bunch.bunch.dto.BunchSocialCard
import com.bunch.bunch.dto.FollowState
import com.bunch.member.domain.Gender

data class MemberResponse(
    val memberId: Long,
    val nickname: String?,
    val imageUrl: String?,
    val birthYear: Int?,
    val gender: Gender?,
    val defaultBunch: BunchResponse?
)

data class MemberSocialProfileResponse(
    val memberId: Long,
    val nickname: String?,
    val imageUrl: String?,
    val currentBunchId: Long?,
    val bunches: List<MemberSocialBunchItem>
)

data class MemberSocialBunchItem(
    val bunch: BunchSocialCard,
    val followState: FollowState,
    val followId: Long?,
    val showFollowButton: Boolean
)
