package com.bunch.bundle.controller

import com.bunch.bundle.dto.BundleActor
import com.bunch.bundle.dto.BundleLocationIntentRequest
import com.bunch.bundle.service.BundleLocationService
import com.bunch.global.common.ApiResponse
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.security.JwtSessionDetails
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import jakarta.validation.Valid
import java.util.UUID

@RestController
@RequestMapping("/api/v1/bunches/{bunchId}/bundles/{bundleId}/locations")
class BundleLocationIntentController(
    private val locations: BundleLocationService
) {
    @PostMapping("/intents")
    fun apply(
        authentication: Authentication,
        @PathVariable bunchId: Long,
        @PathVariable bundleId: Long,
        @RequestHeader("X-Request-Id", required = false) requestId: UUID?,
        @Valid
        @RequestBody request: BundleLocationIntentRequest
    ) = ApiResponse.ok(
        locations.applyIntent(
            actor(authentication),
            bunchId,
            bundleId,
            request,
            correlation(requestId)
        )
    )

    private fun actor(authentication: Authentication): BundleActor {
        val memberId = authentication.principal.toString().toLong()
        val session = (authentication.details as? JwtSessionDetails)?.sessionId
            ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
        return BundleActor(memberId, session)
    }

    private fun correlation(value: UUID?) = (value ?: UUID.randomUUID()).toString()
}
