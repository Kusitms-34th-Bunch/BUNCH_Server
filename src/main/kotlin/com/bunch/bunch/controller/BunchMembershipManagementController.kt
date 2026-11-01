package com.bunch.bunch.controller

import com.bunch.bunch.dto.ChangeMembershipRoleRequest
import com.bunch.bunch.dto.OwnerTransferRequest
import com.bunch.bunch.service.BunchMembershipManagementService
import com.bunch.global.common.ApiResponse
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/bunches")
class BunchMembershipManagementController(
    private val membershipManagement: BunchMembershipManagementService
) {
    @GetMapping("/{bunchId}/memberships")
    fun memberships(
        @AuthenticationPrincipal userId: String,
        @PathVariable bunchId: Long
    ) = ApiResponse.ok(membershipManagement.memberships(userId.toLong(), bunchId))

    @PutMapping("/{bunchId}/memberships/{membershipId}/role")
    fun changeRole(
        @AuthenticationPrincipal userId: String,
        @PathVariable bunchId: Long,
        @PathVariable membershipId: Long,
        @RequestBody request: ChangeMembershipRoleRequest
    ) = ApiResponse.ok(
        membershipManagement.changeRole(userId.toLong(), bunchId, membershipId, request)
    )

    @PostMapping("/{bunchId}/owner-transfer")
    fun transferOwner(
        @AuthenticationPrincipal userId: String,
        @PathVariable bunchId: Long,
        @RequestHeader("Idempotency-Key") requestKey: UUID,
        @RequestBody request: OwnerTransferRequest
    ) = ApiResponse.ok(
        membershipManagement.transferOwner(userId.toLong(), bunchId, requestKey, request)
    )
}
