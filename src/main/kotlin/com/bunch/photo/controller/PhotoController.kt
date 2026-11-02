package com.bunch.photo.controller

import com.bunch.global.common.ApiResponse
import com.bunch.global.image.PhotoImageStorage
import com.bunch.photo.dto.PhotoDeleteRequest
import com.bunch.photo.dto.PhotoUploadRequest
import com.bunch.photo.dto.PhotoUploadResponse
import com.bunch.photo.service.PhotoService
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/v1/bunches/{bunchId}/photos")
class PhotoController(
    private val images: PhotoImageStorage,
    private val photoService: PhotoService
) {
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        @AuthenticationPrincipal userId: String,
        @PathVariable bunchId: Long,
        @RequestPart photo: MultipartFile,
        @ModelAttribute request: PhotoUploadRequest
    ): ApiResponse<PhotoUploadResponse> {
        val command = request.toCommand(bunchId, userId.toLong())
        return ApiResponse.ok(PhotoUploadResponse.from(photoService.upload(command, images.inspect(photo))))
    }

    @GetMapping
    fun list(
        @AuthenticationPrincipal userId: String,
        @PathVariable bunchId: Long,
        @RequestParam(required = false) memberId: Long?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "${PhotoService.DEFAULT_SIZE}") size: Int
    ) = ApiResponse.ok(photoService.list(userId.toLong(), bunchId, memberId, cursor, size))

    @GetMapping("/undated")
    fun listUndated(
        @AuthenticationPrincipal userId: String,
        @PathVariable bunchId: Long,
        @RequestParam(required = false) memberId: Long?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "${PhotoService.DEFAULT_SIZE}") size: Int
    ) = ApiResponse.ok(photoService.listUndated(userId.toLong(), bunchId, memberId, cursor, size))

    @GetMapping("/{photoId}")
    fun detail(
        @AuthenticationPrincipal userId: String,
        @PathVariable bunchId: Long,
        @PathVariable photoId: Long
    ) = ApiResponse.ok(photoService.getDetail(userId.toLong(), bunchId, photoId))

    @PostMapping("/delete")
    fun delete(
        @AuthenticationPrincipal userId: String,
        @PathVariable bunchId: Long,
        @RequestBody request: PhotoDeleteRequest
    ) = ApiResponse.ok(photoService.deletePhotos(userId.toLong(), bunchId, request.photoIds))
}
