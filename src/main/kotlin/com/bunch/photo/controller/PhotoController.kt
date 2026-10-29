package com.bunch.photo.controller

import com.bunch.global.common.ApiResponse
import com.bunch.global.image.PhotoImageStorage
import com.bunch.photo.dto.PhotoUploadResponse
import com.bunch.photo.service.PhotoUploadCommand
import com.bunch.photo.service.PhotoUploadService
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@RestController
@RequestMapping("/api/v1/bunches/{bunchId}/photos")
class PhotoController(
    private val images: PhotoImageStorage,
    private val uploads: PhotoUploadService
) {
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        @AuthenticationPrincipal userId: String,
        @PathVariable bunchId: Long,
        @RequestPart photo: MultipartFile,
        @RequestParam uploadBatchId: UUID,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        capturedDate: LocalDate?,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
        capturedTime: LocalTime?
    ): ApiResponse<PhotoUploadResponse> {
        val command = PhotoUploadCommand(bunchId, userId.toLong(), uploadBatchId, capturedDate, capturedTime)
        return ApiResponse.ok(PhotoUploadResponse.from(uploads.upload(command, images.inspect(photo))))
    }
}
