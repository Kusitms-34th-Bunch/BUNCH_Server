package com.bunch.auth.security

import com.bunch.auth.AuthResult
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
        private const val ACCESS_TOKEN_TYPE = "access"
        private const val REFRESH_TOKEN_TYPE = "refresh"
    }

    private val decodedSecretKey = Base64.getDecoder().decode(secretKey)

    init {
        require(decodedSecretKey.size >= 64) {
            "jwt.secret is too short for HS512: ${decodedSecretKey.size * 8} bits. " +
                "Set JWT secret to a Base64 value that decodes to at least 64 bytes."
        }
    }

    private val key = Keys.hmacShaKeyFor(decodedSecretKey)

    fun createAccessToken(userId: Long, role: String): String {
        val now = Date()

        return Jwts.builder()
            .setSubject(userId.toString())
            .setId(UUID.randomUUID().toString())
            .claim(ROLE_CLAIM, role)
            .claim(TOKEN_TYPE_CLAIM, ACCESS_TOKEN_TYPE)
            .setIssuedAt(now)
            .setExpiration(Date(now.time + ACCESS_TOKEN_VALIDITY))
            .signWith(key, SignatureAlgorithm.HS512)
            .compact()
    }

    fun createRefreshToken(userId: Long): String {
        val now = Date()

        return Jwts.builder()
            .setSubject(userId.toString())
            .setId(UUID.randomUUID().toString())
            .claim(TOKEN_TYPE_CLAIM, REFRESH_TOKEN_TYPE)
            .setIssuedAt(now)
            .setExpiration(Date(now.time + REFRESH_TOKEN_VALIDITY))
            .signWith(key, SignatureAlgorithm.HS512)
            .compact()
    }

    fun generateToken(userId: Long, role: String): AuthResult.TokenPair {
        return AuthResult.TokenPair(
            accessToken = createAccessToken(userId, role),
            refreshToken = createRefreshToken(userId)
        )
    }

    fun getRefreshTokenValiditySeconds(): Long = REFRESH_TOKEN_VALIDITY / 1000

    fun validateToken(token: String): Boolean {
        return try {
            Jwts.parserBuilder().setSigningKey(key).build()
                .parseClaimsJws(token)
            true
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
        return parseClaims(token)[TOKEN_TYPE_CLAIM] == ACCESS_TOKEN_TYPE
    }

    fun isRefreshToken(token: String): Boolean {
        return parseClaims(token)[TOKEN_TYPE_CLAIM] == REFRESH_TOKEN_TYPE
    }

    fun getAuthentication(token: String): Authentication {
        val userId = getUserId(token)
        val role = getRole(token)
        val authorities = role?.let { listOf(SimpleGrantedAuthority(it)) } ?: emptyList()
        val principal = userId.toString()

        return UsernamePasswordAuthenticationToken(
            principal,
            null,
            authorities
        )
    }
}
