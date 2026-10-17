package com.bunch.member.controller

import com.bunch.global.common.ApiResponse
import com.bunch.member.domain.MemberRepository
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.member.dto.TermsRequest
import com.bunch.member.service.TermsService
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/auth/terms")
class TermsController(private val terms: TermsService, private val members: MemberRepository) {
    @GetMapping
    fun current(@AuthenticationPrincipal memberId: String): ApiResponse<*> {
        members
            .findById(memberId.toLong())
            .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }
            .requireActive()
        return ApiResponse.ok(terms.current())
    }

    @PostMapping("/agreements")
    fun agree(
        @AuthenticationPrincipal memberId: String,
        @Valid @RequestBody request: TermsRequest
    ): ApiResponse<Unit> {
        terms.agree(memberId.toLong(), request)
        return ApiResponse.empty(Unit)
    }
}
