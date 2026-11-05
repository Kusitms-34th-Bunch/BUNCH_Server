package com.bunch.bunch.controller

import com.bunch.bunch.dto.BunchMembershipExitRequest
import com.bunch.bunch.dto.BunchRemovalRequest
import com.bunch.bunch.service.BunchMembershipExitService
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
class BunchMembershipExitController(
    private val exits: BunchMembershipExitService
) {
    @PostMapping("/{bunchId}/leave")
    fun leave(
        @AuthenticationPrincipal userId: String,
        @PathVariable bunchId: Long,
        @RequestHeader("Idempotency-Key") requestKey: UUID,
        @RequestBody request: BunchMembershipExitRequest
    ) = ApiResponse.ok(exits.leave(userId.toLong(), bunchId, requestKey, request))

    @PostMapping("/{bunchId}/memberships/{membershipId}/remove")
    fun remove(
        @AuthenticationPrincipal userId: String,
        @PathVariable bunchId: Long,
        @PathVariable membershipId: Long,
        @RequestBody request: BunchRemovalRequest
    ) = ApiResponse.ok(exits.remove(userId.toLong(), bunchId, membershipId, request))
}
