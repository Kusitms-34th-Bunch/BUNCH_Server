package com.bunch.member.controller

import com.bunch.global.common.ApiResponse
import com.bunch.member.domain.TermType
import com.bunch.member.service.TermsDocumentService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth/terms")
class TermsDocumentController(private val documents: TermsDocumentService) {
    @GetMapping("/{type}")
    fun get(
        @AuthenticationPrincipal memberId: String,
        @PathVariable type: TermType
    ) = ApiResponse.ok(documents.current(memberId.toLong(), type))
}
