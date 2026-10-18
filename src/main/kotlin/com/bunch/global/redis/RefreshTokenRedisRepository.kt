package com.bunch.global.redis

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Repository
import java.security.MessageDigest
import java.time.Duration

@Repository
class RefreshTokenRedisRepository(
    private val redis: StringRedisTemplate
) : RefreshTokenRepository {
    override fun save(
        sessionId: String,
        userId: Long,
        refreshToken: String,
        expiresInSeconds: Long
    ) {
        check(
            redis.opsForValue()
                .setIfAbsent(
                    key(sessionId),
                    value(userId, refreshToken),
                    Duration.ofSeconds(expiresInSeconds)
                ) == true
        ) {
            "Session already exists"
        }
    }

    override fun isActive(sessionId: String, userId: Long): Boolean =
        redis.opsForValue().get(key(sessionId))?.startsWith("$userId:") == true

    override fun delete(sessionId: String, userId: Long): Boolean =
        redis.execute(DELETE, listOf(key(sessionId)), "$userId:") == 1L

    override fun compareAndRotate(
        sessionId: String,
        userId: Long,
        expectedToken: String,
        newToken: String,
        expiresInSeconds: Long
    ): Boolean = redis.execute(
        ROTATE,
        listOf(key(sessionId)),
        value(userId, expectedToken),
        value(userId, newToken),
        expiresInSeconds.toString()
    ) == 1L

    private fun key(sessionId: String) = "session:$sessionId"

    private fun value(userId: Long, refreshToken: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(refreshToken.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "$userId:$hash"
    }

    companion object {
        private val ROTATE = DefaultRedisScript("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
            return 1
        """.trimIndent(), Long::class.java)
        private val DELETE = DefaultRedisScript("""
            local value = redis.call('GET', KEYS[1])
            if not value or string.sub(value, 1, string.len(ARGV[1])) ~= ARGV[1] then return 0 end
            return redis.call('DEL', KEYS[1])
        """.trimIndent(), Long::class.java)
    }
}
