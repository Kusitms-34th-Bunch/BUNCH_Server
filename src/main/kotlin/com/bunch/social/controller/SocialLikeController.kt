package com.bunch.social.controller

import com.bunch.global.common.ApiResponse
import com.bunch.social.service.SocialLikeService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/bundles/{bundleId}/like")
class SocialLikeController(private val likes: SocialLikeService) {
    @PutMapping
    fun like(@AuthenticationPrincipal userId: String, @PathVariable bundleId: Long) =
        ApiResponse.ok(likes.like(userId.toLong(), bundleId))

    @DeleteMapping
    fun unlike(@AuthenticationPrincipal userId: String, @PathVariable bundleId: Long) =
        ApiResponse.ok(likes.unlike(userId.toLong(), bundleId))

    @GetMapping
    fun summary(@AuthenticationPrincipal userId: String, @PathVariable bundleId: Long) =
        ApiResponse.ok(likes.summary(userId.toLong(), bundleId))
}
