package com.bunch.bunch.controller

import com.bunch.bunch.dto.BunchPublicMembersResponse
import com.bunch.bunch.service.BunchHomeService
import com.bunch.bunch.service.BunchPublicService
import com.bunch.global.common.ApiResponse
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/bunches")
class BunchHomeController(
    private val home: BunchHomeService,
    private val content: BunchPublicService
) {
    @GetMapping("/{bunchId}/home", "/{bunchId}/public-profile")
    fun home(
        @AuthenticationPrincipal memberId: String,
        @PathVariable bunchId: Long
    ) = ApiResponse.ok(home.home(memberId.toLong(), bunchId))

    @GetMapping("/{bunchId}/members")
    fun members(
        @AuthenticationPrincipal memberId: String,
        @PathVariable bunchId: Long
    ) = ApiResponse.ok(content.members(memberId.toLong(), bunchId))

    @GetMapping("/{bunchId}/public-members")
    fun publicMembers(
        @AuthenticationPrincipal memberId: String,
        @PathVariable bunchId: Long
    ) = ApiResponse.ok(BunchPublicMembersResponse(content.members(memberId.toLong(), bunchId)))

    @GetMapping("/{bunchId}/published-bundles", "/{bunchId}/public-bundles")
    fun bundles(
        @AuthenticationPrincipal memberId: String,
        @PathVariable bunchId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ) = ApiResponse.ok(content.bundles(memberId.toLong(), bunchId, page, size))

    @GetMapping("/{bunchId}/published-bundles/{bundleId}", "/{bunchId}/public-bundles/{bundleId}")
    fun bundle(
        @AuthenticationPrincipal memberId: String,
        @PathVariable bunchId: Long,
        @PathVariable bundleId: Long
    ) = ApiResponse.ok(content.bundle(memberId.toLong(), bunchId, bundleId))
}
