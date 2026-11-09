package com.bunch.bunch.controller

import com.bunch.bunch.dto.FollowDecisionRequest
import com.bunch.bunch.dto.BunchReportRequest
import com.bunch.bunch.service.BunchSocialService
import com.bunch.global.common.ApiResponse
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/bunches")
class BunchSocialController(private val social: BunchSocialService) {
    @PutMapping("/{viewerId}/blocks/{targetId}")
    fun block(
        @AuthenticationPrincipal userId: String,
        @PathVariable viewerId: Long,
        @PathVariable targetId: Long
    ): ApiResponse<Unit> {
        social.block(userId.toLong(), viewerId, targetId)
        return ApiResponse.empty(Unit)
    }

    @DeleteMapping("/{viewerId}/blocks/{targetId}")
    fun unblock(
        @AuthenticationPrincipal userId: String,
        @PathVariable viewerId: Long,
        @PathVariable targetId: Long,
        @RequestParam blockId: Long
    ): ApiResponse<Unit> {
        social.unblock(userId.toLong(), viewerId, targetId, blockId)
        return ApiResponse.empty(Unit)
    }

    @GetMapping("/{viewerId}/blocks")
    fun blocks(
        @AuthenticationPrincipal userId: String,
        @PathVariable viewerId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ) = ApiResponse.ok(social.blocks(userId.toLong(), viewerId, page, size))

    @PostMapping("/{viewerId}/reports")
    fun report(
        @AuthenticationPrincipal userId: String,
        @PathVariable viewerId: Long,
        @RequestBody request: BunchReportRequest
    ) = ApiResponse.ok(social.report(userId.toLong(), viewerId, request))

    @PutMapping("/{viewerId}/following/{targetId}")
    fun follow(
        @AuthenticationPrincipal userId: String,
        @PathVariable viewerId: Long,
        @PathVariable targetId: Long
    ) = ApiResponse.ok(social.follow(userId.toLong(), viewerId, targetId))

    @DeleteMapping("/{viewerId}/following/{targetId}")
    fun cancel(
        @AuthenticationPrincipal userId: String,
        @PathVariable viewerId: Long,
        @PathVariable targetId: Long,
        @RequestParam followId: Long
    ) = ApiResponse.ok(social.cancel(userId.toLong(), viewerId, targetId, followId))

    @PostMapping("/{viewerId}/follow-requests/{requestId}/decision")
    fun decide(
        @AuthenticationPrincipal userId: String,
        @PathVariable viewerId: Long,
        @PathVariable requestId: Long,
        @RequestBody request: FollowDecisionRequest
    ) = ApiResponse.ok(social.decide(userId.toLong(), viewerId, requestId, request.decision))

    @GetMapping("/{viewerId}/follow-requests")
    fun requests(
        @AuthenticationPrincipal userId: String,
        @PathVariable viewerId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ) = ApiResponse.ok(social.requests(userId.toLong(), viewerId, page, size))

    @GetMapping("/{targetId}/followers")
    fun followers(
        @AuthenticationPrincipal userId: String,
        @PathVariable targetId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ) = ApiResponse.ok(social.relations(userId.toLong(), targetId, false, page, size))

    @GetMapping("/{targetId}/following")
    fun following(
        @AuthenticationPrincipal userId: String,
        @PathVariable targetId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ) = ApiResponse.ok(social.relations(userId.toLong(), targetId, true, page, size))
}
