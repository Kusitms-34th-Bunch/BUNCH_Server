package com.bunch.social.controller

import com.bunch.global.common.ApiResponse
import com.bunch.social.dto.SocialContentReportRequest
import com.bunch.social.service.SocialContentReportService
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/bundles/{bundleId}")
class SocialContentReportController(private val reports: SocialContentReportService) {
    @PostMapping("/reports")
    fun bundle(
        authentication: Authentication,
        @PathVariable bundleId: Long,
        @RequestBody request: SocialContentReportRequest
    ) = ApiResponse.ok(reports.bundle(userId(authentication), bundleId, request))

    @PostMapping("/comments/{commentId}/reports")
    fun comment(
        authentication: Authentication,
        @PathVariable bundleId: Long,
        @PathVariable commentId: Long,
        @RequestBody request: SocialContentReportRequest
    ) = ApiResponse.ok(reports.comment(userId(authentication), bundleId, commentId, request))

    private fun userId(authentication: Authentication): Long = authentication.principal.toString().toLong()
}
