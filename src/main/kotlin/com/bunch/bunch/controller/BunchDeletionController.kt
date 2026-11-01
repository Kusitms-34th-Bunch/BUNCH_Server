package com.bunch.bunch.controller

import com.bunch.bunch.dto.BunchDeletionRequest
import com.bunch.bunch.service.BunchDeletionService
import com.bunch.global.common.ApiResponse
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/bunches")
class BunchDeletionController(
    private val deletions: BunchDeletionService
) {
    @PostMapping("/{bunchId}/deletion")
    fun delete(
        @AuthenticationPrincipal userId: String,
        @PathVariable bunchId: Long,
        @RequestHeader("Idempotency-Key") requestKey: UUID,
        @RequestBody request: BunchDeletionRequest
    ) = ApiResponse.ok(
        deletions.delete(userId.toLong(), bunchId, requestKey, request)
    )
}
