package com.bunch.global.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import io.jsonwebtoken.security.Keys
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.Date
import java.util.UUID

class TokenProviderTest {
    private val secret = Base64.getEncoder().encodeToString(ByteArray(64) { 7 })
    private val tokenProvider = TokenProvider(secret)

    @Test
    fun `generateToken embeds member role lowercase token types and shared UUID sid`() {
        val pair = tokenProvider.generateToken(7L, "ROLE_USER")
        val sid = tokenProvider.getSessionId(pair.accessToken)
        assertTrue(tokenProvider.validateToken(pair.accessToken))
        assertTrue(tokenProvider.validateToken(pair.refreshToken))
        assertTrue(tokenProvider.isAccessToken(pair.accessToken))
        assertTrue(tokenProvider.isRefreshToken(pair.refreshToken))
        assertEquals(7L, tokenProvider.getUserId(pair.accessToken))
        assertEquals(7L, tokenProvider.getUserId(pair.refreshToken))
        assertEquals("ROLE_USER", tokenProvider.getRole(pair.accessToken))
        assertEquals("access", tokenProvider.parseClaims(pair.accessToken)["tokenType"])
        assertEquals("refresh", tokenProvider.parseClaims(pair.refreshToken)["tokenType"])
        assertEquals(sid, UUID.fromString(sid).toString())
        assertEquals(sid, tokenProvider.getSessionId(pair.refreshToken))
    }

    @Test
    fun `new logins use independent sessions and rotation preserves session with fresh tokens`() {
        val first = tokenProvider.generateToken(7, "ROLE_USER")
        val nextLogin = tokenProvider.generateToken(7, "ROLE_USER")
        val sid = tokenProvider.getSessionId(first.accessToken)
        val rotated = tokenProvider.generateToken(7, "ROLE_USER", sid)
        assertNotEquals(sid, tokenProvider.getSessionId(nextLogin.accessToken))
        assertEquals(sid, tokenProvider.getSessionId(rotated.accessToken))
        assertNotEquals(first.accessToken, rotated.accessToken)
        assertNotEquals(first.refreshToken, rotated.refreshToken)
        val accessClaims = tokenProvider.parseClaims(first.accessToken)
        val refreshClaims = tokenProvider.parseClaims(first.refreshToken)
        assertEquals(7 * 86400L, (accessClaims.expiration.time - accessClaims.issuedAt.time) / 1000)
        assertEquals(14 * 86400L, (refreshClaims.expiration.time - refreshClaims.issuedAt.time) / 1000)
        assertEquals(14 * 86400L, tokenProvider.getRefreshTokenValiditySeconds())
        assertFalse(tokenProvider.isRefreshToken(first.accessToken))
        assertFalse(tokenProvider.isAccessToken(first.refreshToken))
        assertFalse(tokenProvider.validateToken("broken"))
    }

    @Test
    fun `authentication retains string member principal and verified session details`() {
        val pair = tokenProvider.generateToken(9, "ROLE_USER")
        val authentication = tokenProvider.getAuthentication(pair.accessToken)
        assertEquals("9", authentication.principal)
        assertEquals("ROLE_USER", authentication.authorities.single().authority)
        assertEquals(tokenProvider.getSessionId(pair.accessToken), (authentication.details as JwtSessionDetails).sessionId)
        assertThrows(IllegalArgumentException::class.java) { tokenProvider.getAuthentication(pair.refreshToken) }
    }

    @Test
    fun `signed legacy tokens and malformed sid cannot authenticate or validate`() {
        listOf(null, "", "not-a-uuid").forEach { sid ->
            val builder = Jwts.builder().setSubject("9").claim("role", "ROLE_USER")
                .claim("tokenType", "access").setExpiration(Date(System.currentTimeMillis() + 60000))
            if (sid != null) builder.claim("sid", sid)
            val token = builder.signWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(secret)), SignatureAlgorithm.HS512).compact()
            assertFalse(tokenProvider.validateToken(token))
            assertThrows(IllegalArgumentException::class.java) { tokenProvider.getAuthentication(token) }
        }
    }
}
