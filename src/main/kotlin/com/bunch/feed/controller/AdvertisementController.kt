package com.bunch.feed.controller

import com.bunch.feed.service.AdvertisementService
import com.bunch.global.common.ApiResponse
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/feed/advertisements/{advertisementId}")
class AdvertisementController(private val advertisements: AdvertisementService) {
    @GetMapping
    fun read(@AuthenticationPrincipal userId: String, @PathVariable advertisementId: Long) =
        ApiResponse.ok(advertisements.read(userId.toLong(), advertisementId))

    @PutMapping("/pin")
    fun pin(@AuthenticationPrincipal userId: String, @PathVariable advertisementId: Long) =
        ApiResponse.ok(advertisements.pin(userId.toLong(), advertisementId))

    @PutMapping("/hide")
    fun hide(@AuthenticationPrincipal userId: String, @PathVariable advertisementId: Long): ApiResponse<Unit> {
        advertisements.hide(userId.toLong(), advertisementId)
        return ApiResponse.empty(Unit)
    }
}
