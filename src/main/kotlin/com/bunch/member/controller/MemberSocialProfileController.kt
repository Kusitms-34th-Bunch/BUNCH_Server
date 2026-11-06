package com.bunch.member.controller

import com.bunch.global.common.ApiResponse
import com.bunch.member.service.MemberSocialProfileService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/members")
class MemberSocialProfileController(private val profiles: MemberSocialProfileService) {
    @GetMapping("/{memberId}/social-profile")
    fun get(
        @AuthenticationPrincipal userId: String,
        @PathVariable memberId: Long
    ) = ApiResponse.ok(profiles.get(userId.toLong(), memberId))
}
