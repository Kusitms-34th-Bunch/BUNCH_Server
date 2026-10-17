package com.bunch.member.dto

import com.bunch.member.domain.SignupStatus

data class OnboardingResponse(
    val memberId: Long,
    val signupStatus: SignupStatus,
    val termsAccepted: Boolean,
    val defaultBunchId: Long?
)
