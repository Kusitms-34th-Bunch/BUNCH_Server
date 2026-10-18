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
    @Test fun `session stores refresh hash and rotation permits one concurrent reuse with sliding expiry`() {
        val sid = UUID.randomUUID().toString()
        val userId = 7L
        val executor = Executors.newFixedThreadPool(8)
        try {
            tokens.save(sid, userId, "original", 60)
            val stored = redis.opsForValue().get("session:$sid")!!
            assertEquals("$userId:" + digest("original"), stored)
            assertFalse(stored.contains("original"))
            assertTrue(tokens.isActive(sid, userId))
            assertFalse(tokens.isActive(sid, userId + 1))
            assertFalse(tokens.compareAndRotate(sid, userId + 1, "original", "wrong-owner", 120))
            assertFalse(tokens.delete(sid, userId + 1))
            val results = executor.invokeAll((1..16).map { index ->
                Callable { tokens.compareAndRotate(sid, userId, "original", "replacement-$index", 120) }
            }).map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it })
            assertTrue(redis.getExpire("session:$sid", TimeUnit.SECONDS) in 110..120)
            assertTrue(tokens.delete(sid, userId))
            assertFalse(tokens.isActive(sid, userId))
            assertFalse(tokens.delete(sid, userId))
            assertFalse(tokens.compareAndRotate(sid, userId, "original", "new", 120))
        } finally { tokens.delete(sid, userId); executor.shutdownNow() }
    }

    @Test fun `concurrent logout and refresh never resurrect a session or affect another login`() {
        val executor = Executors.newFixedThreadPool(2)
        val otherSid = UUID.randomUUID().toString()
        val userId = 8L
        try {
            tokens.save(otherSid, userId, "other-login", 120)
            repeat(20) {
                val sid = UUID.randomUUID().toString()
                try {
                    tokens.save(sid, userId, "original", 120)
                    val start = java.util.concurrent.CountDownLatch(1)
                    val rotate = executor.submit(Callable {
                        start.await()
                        tokens.compareAndRotate(sid, userId, "original", "new", 120)
                    })
                    val logout = executor.submit(Callable {
                        start.await()
                        tokens.delete(sid, userId)
                    })
                    start.countDown()
                    rotate.get(5, TimeUnit.SECONDS)
                    assertTrue(logout.get(5, TimeUnit.SECONDS))
                    assertFalse(tokens.isActive(sid, userId))
                    assertFalse(tokens.compareAndRotate(sid, userId, "new", "resurrected", 120))
                    assertTrue(tokens.isActive(otherSid, userId))
                } finally { tokens.delete(sid, userId) }
            }
        } finally { tokens.delete(otherSid, userId); executor.shutdownNow() }
    }

    @Test fun `expired session cannot authenticate or refresh`() {
        val sid = UUID.randomUUID().toString()
        try {
            tokens.save(sid, 9, "original", 120)
            redis.expire("session:$sid", java.time.Duration.ZERO)
            assertFalse(tokens.isActive(sid, 9))
            assertFalse(tokens.compareAndRotate(sid, 9, "original", "new", 120))
        } finally { tokens.delete(sid, 9) }
    }

    private fun digest(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

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
