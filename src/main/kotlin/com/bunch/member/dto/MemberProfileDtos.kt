package com.bunch.member.dto

import com.bunch.bunch.domain.ProfileImageAction
import com.bunch.member.domain.Gender

data class MemberProfileRequest(
    val nickname: String,
    val birthYear: Int? = null,
    val gender: Gender? = null,
    val imageAction: ProfileImageAction
)
