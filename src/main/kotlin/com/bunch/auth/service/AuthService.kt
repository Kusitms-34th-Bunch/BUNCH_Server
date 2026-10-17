package com.bunch.auth.service

import com.bunch.global.oidc.SocialTokenVerifier
import com.bunch.global.redis.IdTokenReplayRepository
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenPair
import com.bunch.global.security.TokenProvider
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.oidc.AuthProvider
import com.bunch.member.domain.MemberRepository
import com.bunch.member.service.SocialMemberRegistry
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AuthService(
    private val tokenProvider: TokenProvider,
    private val refreshTokens: RefreshTokenRepository,
    private val replayTokens: IdTokenReplayRepository,
    private val registry: SocialMemberRegistry,
    private val members: MemberRepository,
    verifiers: List<SocialTokenVerifier>
) {
    private val verifiers = verifiers.associateBy { it.provider }

    fun login(provider: AuthProvider, idToken: String, nonce: String? = null): TokenPair {
        val identity = (verifiers[provider] ?: throw BusinessException(ErrorCode.AUTH_PROVIDER_UNAVAILABLE))
            .verify(idToken, nonce)
        if (!replayTokens.reserve(provider, idToken, identity.acceptedUntil)) {
            throw BusinessException(ErrorCode.TOKEN_REPLAY)
        }
        val member = registry.findOrCreate(provider, identity.subject, identity.email)
        member.requireActive()
        val sessionId = UUID.randomUUID().toString()
        val pair = tokenProvider.generateToken(member.id, member.role.name, sessionId)
        refreshTokens.save(sessionId, member.id, pair.refreshToken, tokenProvider.getRefreshTokenValiditySeconds())
        return pair
    }

    fun reissue(refreshToken: String): TokenPair {
        val (userId, sessionId) = try {
            if (!tokenProvider.validateToken(refreshToken) || !tokenProvider.isRefreshToken(refreshToken)) {
                throw BusinessException(ErrorCode.UNAUTHORIZED)
            }
            tokenProvider.getUserId(refreshToken) to tokenProvider.getSessionId(refreshToken)
        } catch (_: RuntimeException) { throw BusinessException(ErrorCode.UNAUTHORIZED) }
        if (!refreshTokens.isActive(sessionId, userId)) throw BusinessException(ErrorCode.UNAUTHORIZED)
        val member = members.findById(userId).orElse(null)
        if (member == null) {
            refreshTokens.delete(sessionId, userId)
            throw BusinessException(ErrorCode.UNAUTHORIZED)
        }
        try {
            member.requireActive()
        } catch (exception: BusinessException) {
            refreshTokens.delete(sessionId, userId)
            throw exception
        }
        val pair = tokenProvider.generateToken(member.id, member.role.name, sessionId)
        if (!refreshTokens.compareAndRotate(sessionId, userId, refreshToken, pair.refreshToken, tokenProvider.getRefreshTokenValiditySeconds())) {
            throw BusinessException(ErrorCode.UNAUTHORIZED)
        }
        return pair
    }

    fun logout(userId: String, sessionId: String) {
        val id = userId.toLongOrNull() ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
        if (!refreshTokens.delete(sessionId, id)) throw BusinessException(ErrorCode.UNAUTHORIZED)
    }
}
