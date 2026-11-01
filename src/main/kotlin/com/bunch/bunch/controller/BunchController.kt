package com.bunch.bunch.controller

import com.bunch.bunch.dto.CreateBunchRequest
import com.bunch.bunch.service.BunchService
import com.bunch.bunch.service.InvitationService
import com.bunch.global.common.ApiResponse
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@RestController
@RequestMapping("/api/v1/bunches")
class BunchController(private val bunches: BunchService, private val invitations: InvitationService) {
    @GetMapping("/name-availability")
    fun nameAvailability(
        @AuthenticationPrincipal userId: String,
        @RequestParam name: String
    ) = ApiResponse.ok(bunches.nameAvailability(userId.toLong(), name))

    @PostMapping
    fun create(
        @AuthenticationPrincipal userId: String,
        @RequestHeader("Idempotency-Key") key: UUID,
        @RequestBody request: CreateBunchRequest
    ) = ApiResponse.ok(bunches.create(userId.toLong(), key, request))

    @PutMapping("/{id}/profile-image", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun updateImage(
        @AuthenticationPrincipal userId: String,
        @PathVariable id: Long,
        @RequestPart photo: MultipartFile
    ) = ApiResponse.ok(bunches.updateProfileImage(userId.toLong(), id, photo))

    @DeleteMapping("/{id}/profile-image")
    fun resetImage(
        @AuthenticationPrincipal userId: String,
        @PathVariable id: Long
    ) = ApiResponse.ok(bunches.resetProfileImage(userId.toLong(), id))

    @GetMapping("/{id}/members")
    fun members(
        @AuthenticationPrincipal userId: String,
        @PathVariable id: Long
    ) = ApiResponse.ok(bunches.members(userId.toLong(), id))

    @PutMapping("/{id}/last-used")
    fun lastUsed(
        @AuthenticationPrincipal userId: String,
        @PathVariable id: Long
    ): ApiResponse<Unit> {
        bunches.updateLastUsed(userId.toLong(), id)
        return ApiResponse.empty(Unit)
    }

    @PostMapping("/{id}/invitations")
    fun issue(
        @AuthenticationPrincipal userId: String,
        @PathVariable id: Long
    ) = ApiResponse.ok(invitations.issue(userId.toLong(), id))
}
