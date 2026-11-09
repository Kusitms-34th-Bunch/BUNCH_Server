package com.bunch.auth.service

import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import com.bunch.member.service.DevMemberRegistry
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import java.util.UUID

@Service
@Profile("(local | dev) & !prod")
class DevTokenService(
    private val registry: DevMemberRegistry,
    private val tokens: TokenProvider,
    private val sessions: RefreshTokenRepository
) {
    fun issue(account: String): String {
        val member = registry.findOrCreate(account).requireActive()
        val sessionId = UUID.randomUUID().toString()
        val pair = tokens.generateToken(member.id, member.role.name, sessionId)
        sessions.save(sessionId, member.id, pair.refreshToken, tokens.getRefreshTokenValiditySeconds())
        return pair.accessToken
    }
}
