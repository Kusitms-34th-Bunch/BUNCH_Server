package com.bunch.bundle.controller

import com.bunch.bundle.dto.BundleActor
import com.bunch.bundle.dto.BundleTitleCrdtRequest
import com.bunch.bundle.service.BundleService
import com.bunch.global.common.ApiResponse
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.security.JwtSessionDetails
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/bunches/{bunchId}/bundles/{id}/title/crdt")
class BundleTitleController(private val bundles: BundleService) {
    @PatchMapping
    fun merge(
        authentication: Authentication,
        @PathVariable bunchId: Long,
        @PathVariable id: Long,
        @RequestHeader("X-Request-Id", required = false) requestId: UUID?,
        @RequestBody request: BundleTitleCrdtRequest
    ) = ApiResponse.ok(
        bundles.mergeTitle(
            BundleActor(
                authentication.principal.toString().toLong(),
                (authentication.details as? JwtSessionDetails)?.sessionId
                    ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
            ),
            bunchId,
            id,
            request.state,
            (requestId ?: UUID.randomUUID()).toString()
        )
    )
}
