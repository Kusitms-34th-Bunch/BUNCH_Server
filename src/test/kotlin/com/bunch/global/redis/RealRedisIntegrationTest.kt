package com.bunch.global.redis

import com.bunch.global.oidc.AuthProvider
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@ActiveProfiles("test")
class RealRedisIntegrationTest @Autowired constructor(
    private val tokens: RefreshTokenRepository,
    private val replay: IdTokenReplayRepository,
    private val redis: StringRedisTemplate
) {
    @Test fun `refresh rotation permits only one concurrent reuse and logout deletes token`() {
        val id = -System.nanoTime()
        val executor = Executors.newFixedThreadPool(8)
        try {
            tokens.save(id, "original", 120)
            val results = executor.invokeAll((1..16).map { index ->
                Callable { tokens.compareAndRotate(id, "original", "replacement-$index", 120) }
            }).map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it })
            assertTrue(tokens.get(id)!!.startsWith("replacement-"))
            assertTrue(redis.getExpire("refresh:$id", TimeUnit.SECONDS) in 110..120)
            tokens.delete(id)
            assertNull(tokens.get(id))
            assertFalse(tokens.compareAndRotate(id, "original", "new", 120))
        } finally { tokens.delete(id); executor.shutdownNow() }
    }
    @Test fun `replay reservation is atomic provider-bound and retains full lifetime`() {
        val value = UUID.randomUUID().toString()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = executor.invokeAll((1..16).map {
                Callable { replay.reserve(AuthProvider.APPLE, value, Instant.now().plusSeconds(90000)) }
            }).map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it })
            assertTrue(replay.reserve(AuthProvider.GOOGLE, value, Instant.now().plusSeconds(90000)))
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
                .joinToString("") { "%02x".format(it) }
            assertTrue(redis.getExpire("oidc:used:APPLE:$digest", TimeUnit.SECONDS) > 89900)
            assertFalse(replay.reserve(AuthProvider.KAKAO, value, Instant.now().minusSeconds(1)))
        } finally {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
                .joinToString("") { "%02x".format(it) }
            AuthProvider.values().forEach { redis.delete("oidc:used:${it.name}:$digest") }
            executor.shutdownNow()
        }
    }
}
