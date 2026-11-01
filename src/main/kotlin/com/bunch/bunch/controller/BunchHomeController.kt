package com.bunch.bunch.controller

import com.bunch.bunch.service.BunchHomeService
import com.bunch.global.common.ApiResponse
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/bunches")
class BunchHomeController(
    private val home: BunchHomeService
) {
    @GetMapping("/{bunchId}/home")
    fun home(
        @AuthenticationPrincipal memberId: String,
        @PathVariable bunchId: Long
    ) = ApiResponse.ok(home.home(memberId.toLong(), bunchId))
}
