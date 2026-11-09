package com.bunch.feed.controller

import com.bunch.feed.service.FeedService
import com.bunch.global.common.ApiResponse
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/feed")
class FeedController(private val feed: FeedService) {
    @GetMapping
    fun get(
        @AuthenticationPrincipal userId: String,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) cursor: String?
    ) = ApiResponse.ok(feed.get(userId.toLong(), size, cursor))
}
