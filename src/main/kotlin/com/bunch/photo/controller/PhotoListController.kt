package com.bunch.photo.controller

import com.bunch.global.common.ApiResponse
import com.bunch.photo.service.PhotoListService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/bunches/{bunchId}/photos")
class PhotoListController(private val photoList: PhotoListService) {
    @GetMapping
    fun list(
        @AuthenticationPrincipal userId: String,
        @PathVariable bunchId: Long,
        @RequestParam(required = false) memberId: Long?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "${PhotoListService.DEFAULT_SIZE}") size: Int
    ) = ApiResponse.ok(photoList.list(userId.toLong(), bunchId, memberId, cursor, size))
}
