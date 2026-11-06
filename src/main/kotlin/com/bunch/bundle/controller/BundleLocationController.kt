package com.bunch.bundle.controller

import com.bunch.bundle.dto.BundleActor
import com.bunch.bundle.dto.BundleLocationsRequest
import com.bunch.bundle.service.BundleLocationService
import com.bunch.global.common.ApiResponse
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.security.JwtSessionDetails
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/bunches/{bunchId}/bundles/{bundleId}/locations")
class BundleLocationController(private val locations: BundleLocationService) {
    @GetMapping
    fun get(
        authentication: Authentication,
        @PathVariable bunchId: Long,
        @PathVariable bundleId: Long
    ) = ApiResponse.ok(locations.get(authentication.principal.toString().toLong(), bunchId, bundleId))

    @PutMapping
    fun saveDraft(
        authentication: Authentication,
        @PathVariable bunchId: Long,
        @PathVariable bundleId: Long,
        @RequestHeader("X-Request-Id", required = false) requestId: UUID?,
        @RequestBody request: BundleLocationsRequest
    ) = ApiResponse.ok(locations.saveDraft(
        actor(authentication), bunchId, bundleId, request, (requestId ?: UUID.randomUUID()).toString()
    ))

    private fun actor(authentication: Authentication): BundleActor {
        val session = (authentication.details as? JwtSessionDetails)?.sessionId
            ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
        return BundleActor(authentication.principal.toString().toLong(), session)
    }
}
