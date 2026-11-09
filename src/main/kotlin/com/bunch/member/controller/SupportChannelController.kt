package com.bunch.member.controller

import com.bunch.global.common.ApiResponse
import com.bunch.member.service.SupportChannelService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/members/support-channel")
class SupportChannelController(private val support: SupportChannelService) {
    @GetMapping
    fun get(
        @AuthenticationPrincipal memberId: String
    ) = ApiResponse.ok(support.current(memberId.toLong()))
}
