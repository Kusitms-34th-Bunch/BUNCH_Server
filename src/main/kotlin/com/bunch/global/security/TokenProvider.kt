package com.bunch.global.security

import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.Jws
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.stereotype.Component
import java.util.*

@Component
class TokenProvider(
    @Value("\${jwt.secret}")
    secretKey: String
) {

    companion object {
        private const val ACCESS_TOKEN_VALIDITY =
            1000L * 60 * 60 * 24 * 7   // 7일
        private const val REFRESH_TOKEN_VALIDITY =
            1000L * 60 * 60 * 24 * 14 // 14일
        private const val WS_TOKEN_VALIDITY = 60_000L
        private const val WS_AUDIENCE = "bunch-ws"
        private const val ROLE_CLAIM = "role"
        private const val TOKEN_TYPE_CLAIM = "tokenType"
        private const val SESSION_ID_CLAIM = "sid"
    }

    private val decodedSecretKey = Base64.getDecoder().decode(secretKey)

    init {
        require(decodedSecretKey.size >= 64) {
            "jwt.secret is too short for HS512: ${decodedSecretKey.size * 8} bits. " +
                "Set JWT secret to a Base64 value that decodes to at least 64 bytes."
        }
    }

    private val key = Keys.hmacShaKeyFor(decodedSecretKey)

    fun createAccessToken(userId: Long, role: String, sessionId: String): String {
        val now = Date()

        return Jwts.builder()
            .setSubject(userId.toString())
            .setId(UUID.randomUUID().toString())
            .claim(ROLE_CLAIM, role)
            .claim(TOKEN_TYPE_CLAIM, TokenType.ACCESS.claim)
            .claim(SESSION_ID_CLAIM, sessionId)
            .setIssuedAt(now)
            .setExpiration(Date(now.time + ACCESS_TOKEN_VALIDITY))
            .signWith(key, SignatureAlgorithm.HS512)
            .compact()
    }

    fun createRefreshToken(userId: Long, sessionId: String): String {
        val now = Date()

        return Jwts.builder()
            .setSubject(userId.toString())
            .setId(UUID.randomUUID().toString())
            .claim(TOKEN_TYPE_CLAIM, TokenType.REFRESH.claim)
            .claim(SESSION_ID_CLAIM, sessionId)
            .setIssuedAt(now)
            .setExpiration(Date(now.time + REFRESH_TOKEN_VALIDITY))
            .signWith(key, SignatureAlgorithm.HS512)
            .compact()
    }

    fun createWsToken(userId: Long, sessionId: String): String {
        require(userId > 0)
        require(UUID.fromString(sessionId).toString() == sessionId)
        val now = Date()
        return Jwts.builder()
            .setSubject(userId.toString())
            .claim(SESSION_ID_CLAIM, sessionId)
            .claim(TOKEN_TYPE_CLAIM, TokenType.WS.claim)
            .setAudience(WS_AUDIENCE)
            .setIssuedAt(now)
            .setExpiration(Date(now.time + WS_TOKEN_VALIDITY))
            .setId(UUID.randomUUID().toString())
            .signWith(key, SignatureAlgorithm.HS512)
            .compact()
    }

    fun generateToken(
        userId: Long,
        role: String,
        sessionId: String = UUID.randomUUID().toString()
    ): TokenPair {
        return TokenPair(
            accessToken = createAccessToken(userId, role, sessionId),
            refreshToken = createRefreshToken(userId, sessionId)
        )
    }

    fun getRefreshTokenValiditySeconds(): Long = REFRESH_TOKEN_VALIDITY / 1000

    fun validateToken(token: String): Boolean {
        return try {
            val claims = parseClaims(token)
            sessionId(claims)
            require(claims.subject.toLong() > 0)
            claims[TOKEN_TYPE_CLAIM] == TokenType.ACCESS.claim ||
                claims[TOKEN_TYPE_CLAIM] == TokenType.REFRESH.claim
        } catch (e: JwtException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    fun parseClaims(token: String): Claims = signedClaims(token).body

    private fun signedClaims(token: String): Jws<Claims> =
        Jwts.parserBuilder()
            .setSigningKey(key)
            .build()
            .parseClaimsJws(token)

    fun getUserId(token: String): Long {
        return parseClaims(token).subject.toLong()
    }

    fun getRole(token: String): String? {
        return parseClaims(token)[ROLE_CLAIM] as? String
    }

    fun isAccessToken(token: String): Boolean {
        return parseClaims(token)[TOKEN_TYPE_CLAIM] == TokenType.ACCESS.claim
    }

    fun isRefreshToken(token: String): Boolean {
        return parseClaims(token)[TOKEN_TYPE_CLAIM] == TokenType.REFRESH.claim
    }

    // Used only to reject WS bearers on REST, including already expired signed tokens.
    fun isWsToken(token: String): Boolean = try {
        parseClaims(token)[TOKEN_TYPE_CLAIM] == TokenType.WS.claim
    } catch (expired: ExpiredJwtException) {
        // JJWT verifies the signature before checking expiration; never authenticate from these claims.
        expired.claims[TOKEN_TYPE_CLAIM] == TokenType.WS.claim
    } catch (_: RuntimeException) {
        false
    }

    fun getConnectAuthentication(token: String): Authentication {
        val signed = signedClaims(token)
        val claims = signed.body
        if (claims[TOKEN_TYPE_CLAIM] == TokenType.WS.claim) {
            require(signed.header.algorithm == SignatureAlgorithm.HS512.value) { "Invalid WS algorithm" }
            require(claims.audience == WS_AUDIENCE) { "Invalid WS audience" }
            val issuedAt = requireNotNull(claims.issuedAt) { "Missing WS issued time" }
            val expiresAt = requireNotNull(claims.expiration) { "Missing WS expiration" }
            require(!issuedAt.after(Date())) { "Future WS issued time" }
            require(expiresAt.time - issuedAt.time == WS_TOKEN_VALIDITY) { "Invalid WS lifetime" }
            require(!claims.id.isNullOrBlank()) { "Missing WS token ID" }
        } else {
            require(claims[TOKEN_TYPE_CLAIM] == TokenType.ACCESS.claim) { "CONNECT token required" }
        }
        return authentication(claims)
    }

    fun getSessionId(token: String): String = sessionId(parseClaims(token))

    private fun sessionId(claims: Claims): String {
        val sessionId = claims[SESSION_ID_CLAIM] as? String
            ?: throw IllegalArgumentException("Missing session ID")
        require(UUID.fromString(sessionId).toString() == sessionId) { "Invalid session ID" }
        return sessionId
    }

    fun getAuthentication(token: String): Authentication {
        val claims = parseClaims(token)
        require(claims[TOKEN_TYPE_CLAIM] == TokenType.ACCESS.claim) { "Access token required" }
        return authentication(claims)
    }

    private fun authentication(claims: Claims): Authentication {
        val userId = claims.subject?.toLongOrNull()?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("Invalid member ID")
        val role = if (claims[TOKEN_TYPE_CLAIM] == TokenType.ACCESS.claim) claims[ROLE_CLAIM] as? String else null
        val authorities = role?.let { listOf(SimpleGrantedAuthority(it)) } ?: emptyList()
        return UsernamePasswordAuthenticationToken(userId.toString(), null, authorities).apply {
            details = JwtSessionDetails(sessionId(claims))
        }
    }
}
