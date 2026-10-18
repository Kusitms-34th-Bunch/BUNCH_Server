package com.bunch.member.service

import com.bunch.bunch.service.BunchService
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.member.domain.AccountStatus
import com.bunch.member.domain.MemberRepository
import com.bunch.member.domain.SignupStatus
import com.bunch.member.dto.MemberResponse
import org.springframework.stereotype.Service

@Service
class MemberService(
    private val members: MemberRepository,
    private val sessions: RefreshTokenRepository,
    private val bunches: BunchService
) {
    fun get(memberId: Long, sessionId: String): MemberResponse {
        val member = members.findById(memberId).orElse(null)
        if (member == null || member.accountStatus != AccountStatus.ACTIVE) {
            sessions.delete(sessionId, memberId)
            throw BusinessException(ErrorCode.UNAUTHORIZED)
        }
        return MemberResponse(
            member.id,
            member.nickname,
            member.imageUrl,
            member.birthYear,
            member.gender,
            if (member.signupStatus == SignupStatus.COMPLETED) bunches.defaultBunch(member.id) else null
        )
    }
}
