package com.bunch.bunch.controller

import com.bunch.bunch.service.BunchAccessNoticeService
import com.bunch.global.common.ApiResponse
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/members/me/bunch-access-notices")
class BunchAccessNoticeController(
    private val notices: BunchAccessNoticeService
) {
    @GetMapping("/{noticeId}")
    fun get(
        @AuthenticationPrincipal userId: String,
        @PathVariable noticeId: Long
    ) = ApiResponse.ok(notices.get(userId.toLong(), noticeId))

    @DeleteMapping("/{noticeId}")
    fun delete(
        @AuthenticationPrincipal userId: String,
        @PathVariable noticeId: Long
    ): ApiResponse<Unit> {
        notices.delete(userId.toLong(), noticeId)
        return ApiResponse.empty(Unit)
    }
}
