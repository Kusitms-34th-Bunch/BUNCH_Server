package com.bunch.auth

import com.bunch.auth.oidc.SocialTokenVerifier
import com.bunch.auth.redis.IdTokenReplayRepository
import com.bunch.auth.redis.RefreshTokenRepository
import com.bunch.auth.security.TokenProvider
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.member.AuthProvider
import com.bunch.member.MemberRepository
import com.bunch.member.SocialMemberRegistry
import org.springframework.stereotype.Service

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

    fun login(provider: AuthProvider, idToken: String, nonce: String? = null): AuthResult.TokenPair {
        val identity = (verifiers[provider] ?: throw BusinessException(ErrorCode.AUTH_PROVIDER_UNAVAILABLE))
            .verify(idToken, nonce)
        if (!replayTokens.reserve(provider, idToken, identity.acceptedUntil)) {
            throw BusinessException(ErrorCode.TOKEN_REPLAY)
        }
        val member = registry.findOrCreate(provider, identity.subject, identity.email)
        val pair = tokenProvider.generateToken(member.id, member.role)
        refreshTokens.save(member.id, pair.refreshToken, tokenProvider.getRefreshTokenValiditySeconds())
        return pair
    }

    fun reissue(refreshToken: String): AuthResult.TokenPair {
        val userId = try {
            if (!tokenProvider.validateToken(refreshToken) || !tokenProvider.isRefreshToken(refreshToken)) {
                throw BusinessException(ErrorCode.UNAUTHORIZED)
            }
            tokenProvider.getUserId(refreshToken)
        } catch (_: RuntimeException) { throw BusinessException(ErrorCode.UNAUTHORIZED) }
        val member = members.findById(userId).orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }
        val pair = tokenProvider.generateToken(member.id, member.role)
        if (!refreshTokens.compareAndRotate(userId, refreshToken, pair.refreshToken, tokenProvider.getRefreshTokenValiditySeconds())) {
            throw BusinessException(ErrorCode.UNAUTHORIZED)
        }
        return pair
    }

    fun logout(userId: String) {
        val id = userId.toLongOrNull() ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
        if (refreshTokens.get(id) == null) throw BusinessException(ErrorCode.ALREADY_LOGGED_OUT)
        refreshTokens.delete(id)
    }
}
