package com.bunch.global.redis

import com.bunch.global.oidc.AuthProvider
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Repository
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

interface IdTokenReplayRepository {
    fun reserve(provider: AuthProvider, idToken: String, acceptedUntil: Instant): Boolean
}

@Repository
class IdTokenReplayRedisRepository(private val redis: StringRedisTemplate) : IdTokenReplayRepository {
    override fun reserve(provider: AuthProvider, idToken: String, acceptedUntil: Instant): Boolean {
        val ttl = Duration.between(Instant.now(), acceptedUntil)
        if (ttl.isNegative || ttl.isZero) return false
        val digest = MessageDigest.getInstance("SHA-256").digest(idToken.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return redis.opsForValue().setIfAbsent("oidc:used:${provider.name}:$digest", "1", ttl) == true
    }
}
