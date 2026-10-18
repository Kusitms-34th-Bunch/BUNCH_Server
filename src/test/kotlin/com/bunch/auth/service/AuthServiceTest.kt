package com.bunch.auth.service

import com.bunch.global.oidc.AuthProvider
import com.bunch.global.oidc.SocialIdentity
import com.bunch.global.oidc.SocialTokenVerifier
import com.bunch.global.redis.IdTokenReplayRepository
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.member.domain.*
import com.bunch.member.service.SocialMemberRegistry
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
    private val verifierList = AuthProvider.entries.map { provider -> object : SocialTokenVerifier {
        override val provider = provider
        override fun verify(idToken: String, nonce: String?) = SocialIdentity("subject", Instant.now().plusSeconds(300))
    } }
    private val replay = object : IdTokenReplayRepository {
        override fun reserve(provider: AuthProvider, idToken: String, acceptedUntil: Instant) = consumed.add("$provider:$idToken")
    }
    private val service = AuthService(tokens, refresh, replay, registry, members, verifierList)

    @Test fun `all providers create independent sessions and preserve OIDC replay protection`() {
        AuthProvider.entries.forEachIndexed { index, provider ->
            val member = Member(id = index + 1L)
            `when`(registry.findOrCreate(provider, "subject")).thenReturn(member)
            `when`(members.findById(member.id)).thenReturn(Optional.of(member))
            val first = service.login(provider, "first")
            val second = service.login(provider, "second")
            val firstSid = tokens.getSessionId(first.accessToken)
            val secondSid = tokens.getSessionId(second.accessToken)
            assertEquals(member.id, tokens.getUserId(first.accessToken))
            assertNotEquals(firstSid, secondSid)
            assertTrue(refresh.isActive(firstSid, member.id))
            assertTrue(refresh.isActive(secondSid, member.id))
            assertEquals(firstSid, tokens.getSessionId(service.reissue(first.refreshToken).refreshToken))
            verify(registry, times(2)).findOrCreate(provider, "subject")
            assertEquals(ErrorCode.TOKEN_REPLAY, assertThrows(BusinessException::class.java) { service.login(provider, "second") }.errorCode)
        }
    }

    @Test fun `refresh rotation keeps sid and rejects old token and access tokens`() {
        val member = Member(id = 1L)
        `when`(members.findById(1)).thenReturn(Optional.of(member))
        val old = tokens.generateToken(1, member.role.name)
        val sid = tokens.getSessionId(old.refreshToken)
        refresh.save(sid, 1, old.refreshToken, 100)
        val next = service.reissue(old.refreshToken)
        assertNotEquals(old.refreshToken, next.refreshToken)
        assertEquals(sid, tokens.getSessionId(next.refreshToken))
        assertEquals("ROLE_USER", tokens.getRole(next.accessToken))
        assertEquals(14 * 86400L, refresh.lastValiditySeconds)
        assertUnauthorized { service.reissue(old.refreshToken) }
        assertUnauthorized { service.reissue(next.accessToken) }
        assertUnauthorized { service.reissue("malformed") }
        assertTrue(refresh.isActive(sid, 1))
    }

    @Test fun `logout removes only current session and repeated logout is unauthorized`() {
        val first = tokens.generateToken(1, "ROLE_USER")
        val second = tokens.generateToken(1, "ROLE_USER")
        val sid = tokens.getSessionId(first.accessToken)
        val otherSid = tokens.getSessionId(second.accessToken)
        refresh.save(sid, 1, first.refreshToken, 100)
        refresh.save(otherSid, 1, second.refreshToken, 100)
        service.logout("1", sid)
        assertFalse(refresh.isActive(sid, 1))
        assertTrue(refresh.isActive(otherSid, 1))
        assertUnauthorized { service.logout("1", sid) }
        assertUnauthorized { service.logout("2", otherSid) }
        assertUnauthorized { service.logout("not-an-id", otherSid) }
        assertUnauthorized { service.reissue(first.refreshToken) }
    }

    @Test fun `logout racing with refresh cannot restore a deleted session`() {
        val pair = tokens.generateToken(1, "ROLE_USER")
        val sid = tokens.getSessionId(pair.refreshToken)
        refresh.save(sid, 1, pair.refreshToken, 100)
        `when`(members.findById(1)).thenReturn(Optional.of(Member(id = 1)))
        refresh.beforeRotate = { service.logout("1", sid) }
        assertUnauthorized { service.reissue(pair.refreshToken) }
        assertFalse(refresh.isActive(sid, 1))
    }

    @Test fun `missing or inactive member invalidates the current session during refresh`() {
        val memberStates = listOf(null, Member(id = 1, accountStatus = AccountStatus.SUSPENDED),
            Member(id = 1, accountStatus = AccountStatus.WITHDRAWN))
        memberStates.forEach { member ->
            val pair = tokens.generateToken(1, "ROLE_USER")
            val sid = tokens.getSessionId(pair.refreshToken)
            refresh.save(sid, 1, pair.refreshToken, 100)
            `when`(members.findById(1)).thenReturn(Optional.ofNullable(member))
            assertUnauthorized { service.reissue(pair.refreshToken) }
            assertFalse(refresh.isActive(sid, 1))
        }
    }

    @Test fun `inactive members cannot create login sessions`() {
        listOf(AccountStatus.SUSPENDED, AccountStatus.WITHDRAWN).forEach { status ->
            `when`(registry.findOrCreate(AuthProvider.GOOGLE, "subject"))
                .thenReturn(Member(id = 1, accountStatus = status))
            assertUnauthorized { service.login(AuthProvider.GOOGLE, status.name) }
        }
        assertTrue(refresh.values.isEmpty())
    }

    @Test fun `refresh session must belong to signed member`() {
        val pair = tokens.generateToken(1, "ROLE_USER")
        val sid = tokens.getSessionId(pair.refreshToken)
        refresh.save(sid, 2, pair.refreshToken, 100)
        assertUnauthorized { service.reissue(pair.refreshToken) }
        assertTrue(refresh.isActive(sid, 2))
        verifyNoInteractions(members)
    }

    private fun assertUnauthorized(action: () -> Unit) {
        assertEquals(ErrorCode.UNAUTHORIZED, assertThrows(BusinessException::class.java, action).errorCode)
    }

    private class MemoryRefreshTokens : RefreshTokenRepository {
        val values = mutableMapOf<String, Pair<Long, String>>()
        var lastValiditySeconds = 0L
        var beforeRotate: (() -> Unit)? = null
        override fun save(sessionId: String, userId: Long, refreshToken: String, expiresInSeconds: Long) {
            values[sessionId] = userId to refreshToken
            lastValiditySeconds = expiresInSeconds
        }
        override fun isActive(sessionId: String, userId: Long) = values[sessionId]?.first == userId
        override fun delete(sessionId: String, userId: Long): Boolean {
            if (!isActive(sessionId, userId)) return false
            values.remove(sessionId)
            return true
        }
        override fun compareAndRotate(sessionId: String, userId: Long, expectedToken: String, newToken: String, expiresInSeconds: Long): Boolean {
            beforeRotate?.invoke()
            if (values[sessionId] != (userId to expectedToken)) return false
            save(sessionId, userId, newToken, expiresInSeconds)
            return true
        }
    }
}
