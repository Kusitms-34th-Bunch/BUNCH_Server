package com.bunch.photo.controller

import com.bunch.global.common.ApiResponse
import com.bunch.global.image.PhotoImageStorage
import com.bunch.photo.dto.PhotoDeleteRequest
import com.bunch.photo.dto.PhotoUploadRequest
import com.bunch.photo.dto.PhotoUploadResponse
import com.bunch.photo.service.PhotoDeleteService
import com.bunch.photo.service.PhotoUploadService
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/v1/bunches/{bunchId}/photos")
class PhotoController(
    private val images: PhotoImageStorage,
    private val uploads: PhotoUploadService,
    private val photoDelete: PhotoDeleteService
) {
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        @AuthenticationPrincipal userId: String,
        @PathVariable bunchId: Long,
        @RequestPart photo: MultipartFile,
        @ModelAttribute request: PhotoUploadRequest
    ): ApiResponse<PhotoUploadResponse> {
        val command = request.toCommand(bunchId, userId.toLong())
        return ApiResponse.ok(PhotoUploadResponse.from(uploads.upload(command, images.inspect(photo))))
    }

    @PostMapping("/delete")
    fun delete(
        @AuthenticationPrincipal userId: String,
        @PathVariable bunchId: Long,
        @RequestBody request: PhotoDeleteRequest
    ) = ApiResponse.ok(photoDelete.delete(userId.toLong(), bunchId, request.photoIds))
}
