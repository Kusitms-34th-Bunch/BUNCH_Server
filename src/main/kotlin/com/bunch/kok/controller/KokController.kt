package com.bunch.kok.controller

import com.bunch.global.common.ApiResponse
import com.bunch.kok.service.KokService
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class KokController(private val pins: KokService) {
    @GetMapping("/pinned-places")
    fun list(
        authentication: Authentication,
        @RequestParam(required = false) memberId: Long?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") size: Int
    ) = ApiResponse.ok(pins.list(authentication.principal.toString().toLong(), memberId, cursor, size))

    @GetMapping("/bundles/{bundleId}/pinned-places")
    fun forBundle(authentication: Authentication, @PathVariable bundleId: Long) =
        ApiResponse.ok(pins.forBundle(authentication.principal.toString().toLong(), bundleId))

    @PutMapping("/bundles/{bundleId}/locations/{locationId}/pin")
    fun pin(
        authentication: Authentication,
        @PathVariable bundleId: Long,
        @PathVariable locationId: Long
    ) = ApiResponse.ok(pins.pinBundleLocation(authentication.principal.toString().toLong(), bundleId, locationId))

    @DeleteMapping("/pinned-places/{pinId}")
    fun unpin(authentication: Authentication, @PathVariable pinId: Long): ApiResponse<Unit> {
        pins.unpin(authentication.principal.toString().toLong(), pinId)
        return ApiResponse.empty(Unit)
    }
}
