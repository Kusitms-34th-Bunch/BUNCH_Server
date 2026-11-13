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

    @Test
    fun `WS tokens use existing HS512 key with exact audience lifespan and fresh IDs`() {
        val sid = UUID.randomUUID().toString()
        val token = tokenProvider.createWsToken(7, sid)
        val claims = tokenProvider.parseClaims(token)
        assertEquals(setOf("sub", "sid", "tokenType", "aud", "iat", "exp", "jti"), claims.keys)
        assertEquals("7", claims.subject)
        assertEquals(sid, claims["sid"])
        assertEquals("ws", claims["tokenType"])
        assertEquals("bunch-ws", claims.audience)
        assertEquals(60_000L, claims.expiration.time - claims.issuedAt.time)
        assertNotNull(UUID.fromString(claims.id))
        assertNotEquals(token, tokenProvider.createWsToken(7, sid))
        val signed = Jwts.parserBuilder().setSigningKey(Keys.hmacShaKeyFor(Base64.getDecoder().decode(secret)))
            .build().parseClaimsJws(token)
        assertEquals("HS512", signed.header.algorithm)
        val authentication = tokenProvider.getConnectAuthentication(token)
        assertEquals("7", authentication.principal)
        assertEquals(sid, (authentication.details as JwtSessionDetails).sessionId)
        assertTrue(authentication.authorities.isEmpty())
        assertFalse(tokenProvider.validateToken(token))
        assertFalse(tokenProvider.isAccessToken(token))
        assertFalse(tokenProvider.isRefreshToken(token))
        assertThrows(IllegalArgumentException::class.java) { tokenProvider.getAuthentication(token) }
    }

    @Test
    fun `CONNECT rejects WS tokens with missing or malformed required claims`() {
        val now = System.currentTimeMillis() / 1_000
        val invalid = listOf(
            mapOf("sub" to null), mapOf("sub" to "0"), mapOf("sub" to "invalid"),
            mapOf("sid" to null), mapOf("sid" to "invalid"),
            mapOf("tokenType" to null), mapOf("tokenType" to "refresh"),
            mapOf("aud" to null), mapOf("aud" to "other"), mapOf("aud" to listOf("bunch-ws")),
            mapOf("iat" to null), mapOf("exp" to null),
            mapOf("jti" to null), mapOf("jti" to ""),
            mapOf("iat" to now + 10, "exp" to now + 70),
            mapOf("iat" to now - 10, "exp" to now + 3_600)
        )
        invalid.forEach { overrides ->
            assertThrows(RuntimeException::class.java) { tokenProvider.getConnectAuthentication(wsToken(overrides)) }
        }
    }

    @Test
    fun `expired forged and wrong algorithm WS tokens cannot CONNECT`() {
        val now = System.currentTimeMillis() / 1_000
        val expired = wsToken(mapOf("iat" to now - 62, "exp" to now - 2))
        assertThrows(RuntimeException::class.java) { tokenProvider.getConnectAuthentication(expired) }
        assertTrue(tokenProvider.isWsToken(expired))
        val forged = wsToken(secretBytes = ByteArray(64) { 8 })
        assertThrows(RuntimeException::class.java) { tokenProvider.getConnectAuthentication(forged) }
        assertFalse(tokenProvider.isWsToken(forged))
        val wrongAlgorithm = wsToken(algorithm = SignatureAlgorithm.HS256)
        assertThrows(RuntimeException::class.java) { tokenProvider.getConnectAuthentication(wrongAlgorithm) }
        assertTrue(tokenProvider.getConnectAuthentication(wsToken(mapOf("role" to "ROLE_ADMIN"))).authorities.isEmpty())
    }

    @Test
    fun `CONNECT still accepts existing access and rejects refresh tokens`() {
        val pair = tokenProvider.generateToken(7, "ROLE_USER")
        val authentication = tokenProvider.getConnectAuthentication(pair.accessToken)
        assertEquals("7", authentication.principal)
        assertEquals("ROLE_USER", authentication.authorities.single().authority)
        assertEquals(tokenProvider.getSessionId(pair.accessToken), (authentication.details as JwtSessionDetails).sessionId)
        assertThrows(IllegalArgumentException::class.java) { tokenProvider.getConnectAuthentication(pair.refreshToken) }
        assertFalse(tokenProvider.isWsToken(pair.accessToken))
        assertFalse(tokenProvider.isWsToken(pair.refreshToken))
    }

    private fun wsToken(
        overrides: Map<String, Any?> = emptyMap(),
        algorithm: SignatureAlgorithm = SignatureAlgorithm.HS512,
        secretBytes: ByteArray = Base64.getDecoder().decode(secret)
    ): String {
        val now = System.currentTimeMillis() / 1_000
        val claims = mutableMapOf<String, Any>(
            "sub" to "7", "sid" to UUID.randomUUID().toString(), "tokenType" to "ws",
            "aud" to "bunch-ws", "iat" to now - 10, "exp" to now + 50, "jti" to UUID.randomUUID().toString()
        )
        overrides.forEach { (name, value) -> if (value == null) claims.remove(name) else claims[name] = value }
        return Jwts.builder().setClaims(claims).signWith(Keys.hmacShaKeyFor(secretBytes), algorithm).compact()
    }

}
