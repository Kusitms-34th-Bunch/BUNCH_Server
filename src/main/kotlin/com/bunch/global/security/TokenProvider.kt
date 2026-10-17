package com.bunch.global.security

import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwtException
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
    @Value("\${jwt.secret}") secretKey: String
) {

    companion object {
        private const val ACCESS_TOKEN_VALIDITY =
            1000L * 60 * 60 * 24 * 7   // 7일
        private const val REFRESH_TOKEN_VALIDITY =
            1000L * 60 * 60 * 24 * 14 // 14일
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

    fun generateToken(userId: Long, role: String, sessionId: String = UUID.randomUUID().toString()): TokenPair {
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
            TokenType.entries.any { it.claim == claims[TOKEN_TYPE_CLAIM] }
        } catch (e: JwtException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    fun parseClaims(token: String): Claims {
        return Jwts.parserBuilder().setSigningKey(key).build()
            .parseClaimsJws(token).body
    }

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

    fun getSessionId(token: String): String = sessionId(parseClaims(token))

    private fun sessionId(claims: Claims): String {
        val sessionId = claims[SESSION_ID_CLAIM] as? String ?: throw IllegalArgumentException("Missing session ID")
        require(UUID.fromString(sessionId).toString() == sessionId) { "Invalid session ID" }
        return sessionId
    }

    fun getAuthentication(token: String): Authentication {
        val claims = parseClaims(token)
        require(claims[TOKEN_TYPE_CLAIM] == TokenType.ACCESS.claim) { "Access token required" }
        val userId = claims.subject.toLong()
        require(userId > 0)
        val role = claims[ROLE_CLAIM] as? String
        val authorities = role?.let { listOf(SimpleGrantedAuthority(it)) } ?: emptyList()
        return UsernamePasswordAuthenticationToken(userId.toString(), null, authorities).apply {
            details = JwtSessionDetails(sessionId(claims))
        }
    }
}
