package com.bunch.member.controller

import com.bunch.global.common.ApiResponse
import com.bunch.global.security.JwtSessionDetails
import com.bunch.member.dto.MemberProfileRequest
import com.bunch.member.dto.MemberResponse
import com.bunch.member.service.MemberProfileService
import com.bunch.member.service.MemberService
import com.bunch.member.service.MemberSocialProfileService
import org.springframework.http.MediaType
import org.springframework.security.core.Authentication
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/v1/members")
class MemberController(
    private val members: MemberService,
    private val profiles: MemberSocialProfileService,
    private val profileEdits: MemberProfileService
) {
    @GetMapping("/me")
    fun me(
        @AuthenticationPrincipal memberId: String,
        authentication: Authentication
    ) = ApiResponse.ok(
        members.get(memberId.toLong(), (authentication.details as JwtSessionDetails).sessionId)
    )

    @PutMapping("/me/profile", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun updateProfile(
        @AuthenticationPrincipal memberId: String,
        authentication: Authentication,
        @RequestPart("profile") request: MemberProfileRequest,
        @RequestPart("photo", required = false) photo: MultipartFile?
    ): ApiResponse<MemberResponse> {
        profileEdits.update(memberId.toLong(), request, photo)
        return ApiResponse.ok(
            members.get(memberId.toLong(), (authentication.details as JwtSessionDetails).sessionId)
        )
    }

    @GetMapping("/{memberId}/social-profile")
    fun get(
        @AuthenticationPrincipal userId: String,
        @PathVariable memberId: Long
    ) = ApiResponse.ok(profiles.get(userId.toLong(), memberId))
}
