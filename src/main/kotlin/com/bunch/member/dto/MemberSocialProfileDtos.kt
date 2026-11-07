package com.bunch.member.dto

import com.bunch.bunch.dto.BunchSocialCard
import com.bunch.bunch.dto.FollowState

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
