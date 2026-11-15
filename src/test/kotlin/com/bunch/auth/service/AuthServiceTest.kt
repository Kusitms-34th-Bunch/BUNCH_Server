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
import com.bunch.notification.service.PushDeviceService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.dao.DataAccessResourceFailureException
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
    private val pushDevices = mock(PushDeviceService::class.java)
    private val service = AuthService(tokens, refresh, replay, registry, members, verifierList, pushDevices)

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
        `when`(members.findLockedById(1)).thenReturn(Member(id = 1))
        val first = tokens.generateToken(1, "ROLE_USER")
        val second = tokens.generateToken(1, "ROLE_USER")
        val sid = tokens.getSessionId(first.accessToken)
        val otherSid = tokens.getSessionId(second.accessToken)
        refresh.save(sid, 1, first.refreshToken, 100)
        refresh.save(otherSid, 1, second.refreshToken, 100)
        service.logout("1", sid)
        verify(pushDevices).logoutSession(1, java.util.UUID.fromString(sid))
        assertFalse(refresh.isActive(sid, 1))
        assertTrue(refresh.isActive(otherSid, 1))
        assertUnauthorized { service.logout("1", sid) }
        assertUnauthorized { service.logout("2", otherSid) }
        assertUnauthorized { service.logout("not-an-id", otherSid) }
        assertUnauthorized { service.reissue(first.refreshToken) }
    }

    @Test fun `logout racing with refresh cannot restore a deleted session`() {
        `when`(members.findLockedById(1)).thenReturn(Member(id = 1))
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

    @Test
    fun `WS issuance accepts active signup in progress without renewing or rotating session`() {
        val member = Member(id = 1, signupStatus = SignupStatus.IN_PROGRESS)
        `when`(members.findById(1)).thenReturn(Optional.of(member))
        val pair = tokens.generateToken(1, member.role.name)
        val sid = tokens.getSessionId(pair.accessToken)
        refresh.save(sid, 1, pair.refreshToken, 100)
        val before = refresh.values.toMap()

        val first = service.issueWsToken(1, sid)
        val second = service.issueWsToken(1, sid)
        val claims = tokens.parseClaims(first.token)

        assertEquals("1", claims.subject)
        assertEquals(sid, claims["sid"])
        assertEquals("ws", claims["tokenType"])
        assertEquals("bunch-ws", claims.audience)
        assertEquals(60_000L, claims.expiration.time - claims.issuedAt.time)
        assertEquals(claims.expiration.toInstant(), first.expiresAt)
        assertNotEquals(first.token, second.token)
        assertEquals(before, refresh.values)
        assertEquals(100L, refresh.lastValiditySeconds)
        verifyNoInteractions(registry, pushDevices)
    }

    @Test
    fun `WS issuance rejects missing session and a session belonging to another member`() {
        val pair = tokens.generateToken(1, "ROLE_USER")
        val sid = tokens.getSessionId(pair.accessToken)
        assertUnauthorized { service.issueWsToken(1, sid) }
        refresh.save(sid, 2, pair.refreshToken, 100)
        assertUnauthorized { service.issueWsToken(1, sid) }
        assertTrue(refresh.isActive(sid, 2))
        verifyNoInteractions(members)
    }

    @Test
    fun `WS issuance rejects missing and inactive members without changing session`() {
        val pair = tokens.generateToken(1, "ROLE_USER")
        val sid = tokens.getSessionId(pair.accessToken)
        refresh.save(sid, 1, pair.refreshToken, 100)
        val before = refresh.values.toMap()
        val states = listOf(null, Member(id = 1, accountStatus = AccountStatus.SUSPENDED),
            Member(id = 1, accountStatus = AccountStatus.WITHDRAWN))

        states.forEach { member ->
            `when`(members.findById(1)).thenReturn(Optional.ofNullable(member))
            assertUnauthorized { service.issueWsToken(1, sid) }
            assertEquals(before, refresh.values)
            assertEquals(100L, refresh.lastValiditySeconds)
        }
    }

    @Test
    fun `issued WS token cannot rotate refresh session`() {
        `when`(members.findById(1)).thenReturn(Optional.of(Member(id = 1)))
        val pair = tokens.generateToken(1, "ROLE_USER")
        val sid = tokens.getSessionId(pair.accessToken)
        refresh.save(sid, 1, pair.refreshToken, 100)
        val before = refresh.values.toMap()

        assertUnauthorized { service.reissue(service.issueWsToken(1, sid).token) }

        assertEquals(before, refresh.values)
        assertEquals(100L, refresh.lastValiditySeconds)
    }

    @Test
    fun `WS issuance maps session storage failure to unavailable without issuing JWT`() {
        val sessions = mock(RefreshTokenRepository::class.java)
        val issuer = mock(TokenProvider::class.java)
        val sid = java.util.UUID.randomUUID().toString()
        `when`(sessions.isActive(sid, 1))
            .thenThrow(DataAccessResourceFailureException("private connection details"))
        val auth = AuthService(issuer, sessions, replay, registry, members, verifierList, pushDevices)

        val exception = assertThrows(BusinessException::class.java) { auth.issueWsToken(1, sid) }

        assertEquals(ErrorCode.AUTH_SESSION_UNAVAILABLE, exception.errorCode)
        verifyNoInteractions(issuer, members, registry, pushDevices)
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
        override fun deleteAll(userId: Long): Int {
            val sessionIds = values.filterValues { it.first == userId }.keys
            sessionIds.forEach(values::remove)
            return sessionIds.size
        }
        override fun compareAndRotate(sessionId: String, userId: Long, expectedToken: String, newToken: String, expiresInSeconds: Long): Boolean {
            beforeRotate?.invoke()
            if (values[sessionId] != (userId to expectedToken)) return false
            save(sessionId, userId, newToken, expiresInSeconds)
            return true
        }
    }
}
