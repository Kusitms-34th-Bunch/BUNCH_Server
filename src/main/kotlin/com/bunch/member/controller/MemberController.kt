package com.bunch.member.controller

import com.bunch.global.common.ApiResponse
import com.bunch.global.security.JwtSessionDetails
import com.bunch.member.service.MemberService
import org.springframework.security.core.Authentication
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/members")
class MemberController(private val members: MemberService) {
    @GetMapping("/me")
    fun me(
        @AuthenticationPrincipal memberId: String,
        authentication: Authentication
    ) = ApiResponse.ok(
        members.get(memberId.toLong(), (authentication.details as JwtSessionDetails).sessionId)
    )
}
