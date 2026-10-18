package com.bunch.member.controller

import com.bunch.global.common.ApiResponse
import com.bunch.global.security.JwtSessionDetails
import com.bunch.member.service.OnboardingService
import org.springframework.security.core.Authentication
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/members")
class OnboardingController(private val onboarding: OnboardingService) {
    @GetMapping("/me/onboarding")
    fun get(
        @AuthenticationPrincipal memberId: String,
        authentication: Authentication
    ) = ApiResponse.ok(onboarding.get(memberId.toLong(), (authentication.details as JwtSessionDetails).sessionId))
}
