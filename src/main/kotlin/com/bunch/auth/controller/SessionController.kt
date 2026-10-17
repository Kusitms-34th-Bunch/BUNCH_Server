package com.bunch.auth.controller

import com.bunch.bunch.service.BunchService
import com.bunch.global.common.ApiResponse
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.JwtSessionDetails
import com.bunch.member.domain.AccountStatus
import com.bunch.member.domain.MemberRepository
import com.bunch.member.domain.SignupStatus
import com.bunch.member.service.TermsService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

data class SessionResponse(val memberId: Long, val signupStatus: SignupStatus, val termsAccepted: Boolean, val defaultBunchId: Long?)

@RestController
class SessionController(private val members: MemberRepository, private val terms: TermsService,
    private val bunches: BunchService, private val sessions: RefreshTokenRepository) {
    @GetMapping("/api/v1/auth/session")
    fun session(@AuthenticationPrincipal memberId: String, authentication: Authentication): ApiResponse<SessionResponse> {
        val member = members.findById(memberId.toLong()).orElse(null)
        if (member == null || member.accountStatus != AccountStatus.ACTIVE) {
            val session = authentication.details as JwtSessionDetails
            sessions.delete(session.sessionId, memberId.toLong())
            throw BusinessException(ErrorCode.UNAUTHORIZED)
        }
        return ApiResponse.ok(SessionResponse(member.id, member.signupStatus, terms.accepted(member.id),
            if (member.signupStatus == SignupStatus.COMPLETED) bunches.defaultBunchId(member.id) else null))
    }
}
