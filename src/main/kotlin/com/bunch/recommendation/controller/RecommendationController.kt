package com.bunch.recommendation.controller

import com.bunch.global.common.ApiResponse
import com.bunch.recommendation.service.RecommendationService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/bunches/{bunchId}/recommended-bundles")
class RecommendationController(private val recommendations: RecommendationService) {
    @GetMapping
    fun list(
        @AuthenticationPrincipal userId: String,
        @PathVariable bunchId: Long
    ) = ApiResponse.ok(recommendations.bundleCards(userId.toLong(), bunchId))

    @GetMapping("/{id}")
    fun detail(
        @AuthenticationPrincipal userId: String,
        @PathVariable bunchId: Long,
        @PathVariable id: Long
    ) = ApiResponse.ok(recommendations.bundlePhotos(userId.toLong(), bunchId, id))
}
