package com.bunch.bunch.controller

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
class BunchPublicController(private val public: BunchPublicService) {
    @GetMapping("/{targetId}/public-profile")
    fun profile(
        @AuthenticationPrincipal userId: String,
        @PathVariable targetId: Long,
        @RequestParam(required = false) viewerBunchId: Long?
    ) = ApiResponse.ok(public.profile(userId.toLong(), targetId, viewerBunchId))

    @GetMapping("/{targetId}/public-members")
    fun members(
        @AuthenticationPrincipal userId: String,
        @PathVariable targetId: Long,
        @RequestParam(required = false) viewerBunchId: Long?
    ) = ApiResponse.ok(public.members(userId.toLong(), targetId, viewerBunchId))

    @GetMapping("/{targetId}/public-bundles")
    fun bundles(
        @AuthenticationPrincipal userId: String,
        @PathVariable targetId: Long,
        @RequestParam(required = false) viewerBunchId: Long?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ) = ApiResponse.ok(public.bundles(userId.toLong(), targetId, viewerBunchId, page, size))

    @GetMapping("/{targetId}/public-bundles/{bundleId}")
    fun bundle(
        @AuthenticationPrincipal userId: String,
        @PathVariable targetId: Long,
        @PathVariable bundleId: Long,
        @RequestParam(required = false) viewerBunchId: Long?
    ) = ApiResponse.ok(public.bundle(userId.toLong(), targetId, bundleId, viewerBunchId))
}
