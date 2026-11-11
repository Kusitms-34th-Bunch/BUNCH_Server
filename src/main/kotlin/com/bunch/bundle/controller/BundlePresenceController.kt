package com.bunch.bundle.controller

import com.bunch.bundle.service.BundlePresenceService
import com.bunch.global.common.ApiResponse
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/bunches/{bunchId}/bundles/{bundleId}/presence")
class BundlePresenceController(
    private val presence: BundlePresenceService
) {
    @GetMapping
    fun get(
        authentication: Authentication,
        @PathVariable bunchId: Long,
        @PathVariable bundleId: Long
    ) = ApiResponse.ok(presence.get(authentication.principal.toString().toLong(), bunchId, bundleId))
}
