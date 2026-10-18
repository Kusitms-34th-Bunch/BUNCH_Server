package com.bunch.member.dto

import com.bunch.member.domain.Gender
import com.bunch.member.domain.SignupStatus

data class MemberResponse(
    val memberId: Long,
    val signupStatus: SignupStatus,
    val nickname: String?,
    val imageUrl: String?,
    val birthYear: Int?,
    val gender: Gender?
)
