package com.bunch.member.dto

import com.bunch.bunch.domain.ProfileImageAction

data class MemberProfileRequest(
    val nickname: String,
    val imageAction: ProfileImageAction
)
