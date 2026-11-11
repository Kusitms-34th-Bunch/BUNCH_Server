package com.bunch.member.controller

import com.bunch.global.common.ApiResponse
import com.bunch.member.service.MemberWithdrawalService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/members/me/withdrawal")
class MemberWithdrawalController(private val withdrawals: MemberWithdrawalService) {
    @GetMapping("/check")
    fun check(
        @AuthenticationPrincipal memberId: String
    ) = ApiResponse.ok(withdrawals.check(memberId.toLong()))

    @PostMapping
    fun withdraw(
        @AuthenticationPrincipal memberId: String
    ): ApiResponse<Unit> {
        withdrawals.withdraw(memberId.toLong())
        return ApiResponse.empty(Unit)
    }
}
