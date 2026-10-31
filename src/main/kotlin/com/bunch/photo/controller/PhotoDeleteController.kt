package com.bunch.photo.controller

import com.bunch.global.common.ApiResponse
import com.bunch.photo.dto.PhotoDeleteRequest
import com.bunch.photo.service.PhotoDeleteService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/bunches/{bunchId}/photos")
class PhotoDeleteController(private val photoDelete: PhotoDeleteService) {
    @PostMapping("/delete")
    fun delete(
        @AuthenticationPrincipal userId: String,
        @PathVariable bunchId: Long,
        @RequestBody request: PhotoDeleteRequest
    ) = ApiResponse.ok(photoDelete.delete(userId.toLong(), bunchId, request.photoIds))
}
