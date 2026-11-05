package com.bunch.bunch.controller

import com.bunch.bunch.service.BunchListService
import com.bunch.global.common.ApiResponse
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/members/me")
class BunchListController(
    private val bunches: BunchListService
) {
    @GetMapping("/bunches")
    fun list(
        @AuthenticationPrincipal memberId: String
    ) = ApiResponse.ok(bunches.list(memberId.toLong()))
}
