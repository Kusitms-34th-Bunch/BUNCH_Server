package com.bunch.bunch.controller

import com.bunch.bunch.dto.BunchProfileRequest
import com.bunch.bunch.service.BunchProfileService
import com.bunch.global.common.ApiResponse
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@RestController
@RequestMapping("/api/v1/bunches")
class BunchProfileController(
    private val profiles: BunchProfileService
) {
    @PutMapping("/{bunchId}/profile", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun update(
        @AuthenticationPrincipal memberId: String,
        @PathVariable bunchId: Long,
        @RequestHeader("Idempotency-Key") requestKey: UUID,
        @RequestPart("profile") request: BunchProfileRequest,
        @RequestPart("photo", required = false) photo: MultipartFile?
    ) = ApiResponse.ok(profiles.update(memberId.toLong(), bunchId, requestKey, request, photo))
}
