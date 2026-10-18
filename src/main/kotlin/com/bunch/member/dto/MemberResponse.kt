package com.bunch.member.dto

import com.bunch.bunch.dto.BunchResponse
import com.bunch.member.domain.Gender

data class MemberResponse(
    val memberId: Long,
    val nickname: String?,
    val imageUrl: String?,
    val birthYear: Int?,
    val gender: Gender?,
    val defaultBunch: BunchResponse?
)
