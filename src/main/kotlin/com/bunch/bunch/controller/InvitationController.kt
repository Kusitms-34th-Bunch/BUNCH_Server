package com.bunch.bunch.controller

import com.bunch.bunch.dto.InvitationRequest
import com.bunch.bunch.service.InvitationService
import com.bunch.global.common.ApiResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/invitations")
class InvitationController(private val invitations: InvitationService) {
    @GetMapping("/{token}")
    fun preview(@PathVariable token: String, request: HttpServletRequest) =
        ApiResponse.ok(invitations.publicPreview(token, request.remoteAddr))

    @PostMapping("/verify")
    fun verify(@AuthenticationPrincipal userId: String, @RequestBody request: InvitationRequest) =
        ApiResponse.ok(invitations.verify(userId.toLong(), request.invitation))

    @PostMapping("/join")
    fun join(
        @AuthenticationPrincipal userId: String,
        @RequestHeader("Idempotency-Key") key: UUID,
        @RequestBody request: InvitationRequest
    ) = ApiResponse.ok(invitations.join(userId.toLong(), key, request.invitation))
}
