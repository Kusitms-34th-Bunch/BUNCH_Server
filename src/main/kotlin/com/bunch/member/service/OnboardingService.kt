package com.bunch.member.service

import com.bunch.bunch.service.BunchService
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.member.domain.AccountStatus
import com.bunch.member.domain.MemberRepository
import com.bunch.member.domain.SignupStatus
import com.bunch.member.dto.OnboardingResponse
import org.springframework.stereotype.Service

@Service
class OnboardingService(
    private val members: MemberRepository,
    private val terms: TermsService,
    private val bunches: BunchService,
    private val sessions: RefreshTokenRepository
) {
    fun get(memberId: Long, sessionId: String): OnboardingResponse {
        val member = members.findById(memberId).orElse(null)
        if (member == null || member.accountStatus != AccountStatus.ACTIVE) {
            sessions.delete(sessionId, memberId)
            throw BusinessException(ErrorCode.UNAUTHORIZED)
        }
        return OnboardingResponse(
            member.id,
            member.signupStatus,
            terms.accepted(member.id),
            if (member.signupStatus == SignupStatus.COMPLETED) bunches.defaultBunchId(member.id) else null
        )
    }
}
