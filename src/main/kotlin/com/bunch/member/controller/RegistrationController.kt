package com.bunch.member.controller

import com.bunch.global.common.ApiResponse
import com.bunch.member.dto.RegistrationRequest
import com.bunch.member.service.RegistrationService
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/v1/members")
class RegistrationController(private val registrations: RegistrationService) {
    @GetMapping("/nickname-availability")
    fun nickname(@AuthenticationPrincipal memberId: String, @RequestParam nickname: String) =
        ApiResponse.ok(registrations.nicknameAvailable(memberId.toLong(), nickname))

    @PostMapping("/me/registration", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun complete(@AuthenticationPrincipal memberId: String,
        @Valid @RequestPart("profile") profile: RegistrationRequest,
        @RequestPart("photo", required = false) photo: MultipartFile?) =
        ApiResponse.ok(registrations.complete(memberId.toLong(), profile, photo))
}
