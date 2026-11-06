package com.bunch.social.controller

import com.bunch.global.common.ApiResponse
import com.bunch.social.dto.CreateSocialCommentRequest
import com.bunch.social.service.SocialCommentService
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/bundles/{bundleId}/comments")
class SocialCommentController(private val comments: SocialCommentService) {
    @GetMapping
    fun list(
        authentication: Authentication,
        @PathVariable bundleId: Long,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) cursor: String?
    ) = ApiResponse.ok(comments.list(userId(authentication), bundleId, size = size, cursor = cursor))

    @GetMapping("/{commentId}/replies")
    fun replies(
        authentication: Authentication,
        @PathVariable bundleId: Long,
        @PathVariable commentId: Long,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) cursor: String?
    ) = ApiResponse.ok(comments.list(userId(authentication), bundleId, commentId, size, cursor))

    @PostMapping
    fun create(
        authentication: Authentication,
        @PathVariable bundleId: Long,
        @RequestHeader("Idempotency-Key") key: UUID,
        @RequestBody request: CreateSocialCommentRequest
    ) = ApiResponse.ok(comments.create(userId(authentication), bundleId, key, request))

    @DeleteMapping("/{commentId}")
    fun delete(
        authentication: Authentication,
        @PathVariable bundleId: Long,
        @PathVariable commentId: Long
    ) = ApiResponse.ok(comments.delete(userId(authentication), bundleId, commentId))

    private fun userId(authentication: Authentication): Long = authentication.principal.toString().toLong()
}
