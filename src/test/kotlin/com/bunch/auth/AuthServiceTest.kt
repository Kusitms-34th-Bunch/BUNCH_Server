package com.bunch.auth

import com.bunch.auth.oidc.SocialIdentity
import com.bunch.auth.oidc.SocialTokenVerifier
import com.bunch.auth.redis.IdTokenReplayRepository
import com.bunch.auth.redis.RefreshTokenRepository
import com.bunch.auth.security.TokenProvider
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.member.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.time.Instant
import java.util.Base64
import java.util.Optional

class AuthServiceTest {
    private val tokens = TokenProvider(Base64.getEncoder().encodeToString(ByteArray(64) { 7 }))
    private val registry = mock(SocialMemberRegistry::class.java)
    private val members = mock(MemberRepository::class.java)
    private val consumed = mutableSetOf<String>()
    private val refresh = MemoryRefreshTokens()
    private val verifierList = AuthProvider.values().map { provider -> object : SocialTokenVerifier {
        override val provider = provider
        override fun verify(idToken: String, nonce: String?) = SocialIdentity("subject", null, Instant.now().plusSeconds(300))
    } }
    private val replay = object : IdTokenReplayRepository {
        override fun reserve(provider: AuthProvider, idToken: String, acceptedUntil: Instant) = consumed.add("$provider:$idToken")
    }
    private val service = AuthService(tokens, refresh, replay, registry, members, verifierList)

    @Test fun `all providers create or retrieve member and replace session`() {
        AuthProvider.values().forEachIndexed { index, provider ->
            val member = Member(id = index + 1L)
            `when`(registry.findOrCreate(provider, "subject", null)).thenReturn(member)
            val first = service.login(provider, "first")
            val second = service.login(provider, "second")
            assertEquals(member.id, tokens.getUserId(first.accessToken))
            assertEquals(second.refreshToken, refresh.get(member.id))
            verify(registry, times(2)).findOrCreate(provider, "subject", null)
            assertThrows(BusinessException::class.java) { service.reissue(first.refreshToken) }
            assertEquals(ErrorCode.TOKEN_REPLAY, assertThrows(BusinessException::class.java) { service.login(provider, "second") }.errorCode)
        }
    }
    @Test fun `refresh rotation rejects old token and access tokens`() {
        val member = Member(id = 1L, role = "ROLE_ADMIN")
        `when`(members.findById(1)).thenReturn(Optional.of(member))
        val old = tokens.generateToken(1, member.role)
        refresh.save(1, old.refreshToken, 100)
        val next = service.reissue(old.refreshToken)
        assertNotEquals(old.refreshToken, next.refreshToken)
        assertEquals("ROLE_ADMIN", tokens.getRole(next.accessToken))
        assertThrows(BusinessException::class.java) { service.reissue(old.refreshToken) }
        assertThrows(BusinessException::class.java) { service.reissue(next.accessToken) }
        assertThrows(BusinessException::class.java) { service.reissue("malformed") }
    }
    @Test fun `logout removes refresh while retained access remains valid`() {
        val pair = tokens.generateToken(1, "ROLE_USER")
        refresh.save(1, pair.refreshToken, 100)
        service.logout("1")
        assertNull(refresh.get(1))
        assertTrue(tokens.validateToken(pair.accessToken))
        assertEquals(ErrorCode.ALREADY_LOGGED_OUT, assertThrows(BusinessException::class.java) { service.logout("1") }.errorCode)
        assertThrows(BusinessException::class.java) { service.logout("not-an-id") }
    }
    @Test fun `absent member rejects refresh`() {
        `when`(members.findById(1)).thenReturn(Optional.empty())
        assertEquals(ErrorCode.USER_NOT_FOUND, assertThrows(BusinessException::class.java) {
            service.reissue(tokens.createRefreshToken(1))
        }.errorCode)
    }
    private class MemoryRefreshTokens : RefreshTokenRepository {
        private val values = mutableMapOf<Long, String>()
        override fun save(userId: Long, refreshToken: String, expiresInSeconds: Long) { values[userId] = refreshToken }
        override fun get(userId: Long) = values[userId]
        override fun delete(userId: Long) { values.remove(userId) }
        override fun compareAndRotate(userId: Long, expectedToken: String, newToken: String, expiresInSeconds: Long): Boolean {
            if (values[userId] != expectedToken) return false
            values[userId] = newToken
            return true
        }
    }
}
