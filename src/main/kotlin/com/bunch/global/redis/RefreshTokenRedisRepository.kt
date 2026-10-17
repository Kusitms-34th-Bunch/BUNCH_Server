package com.bunch.global.redis

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Repository
import java.time.Duration

@Repository
class RefreshTokenRedisRepository(private val redis: StringRedisTemplate) : RefreshTokenRepository {
    override fun save(userId: Long, refreshToken: String, expiresInSeconds: Long) {
        redis.opsForValue().set("refresh:$userId", refreshToken, Duration.ofSeconds(expiresInSeconds))
    }
    override fun get(userId: Long): String? = redis.opsForValue().get("refresh:$userId")
    override fun delete(userId: Long) { redis.delete("refresh:$userId") }
    override fun compareAndRotate(userId: Long, expectedToken: String, newToken: String, expiresInSeconds: Long): Boolean =
        redis.execute(ROTATE, listOf("refresh:$userId"), expectedToken, newToken, expiresInSeconds.toString()) == 1L

    companion object {
        private val ROTATE = DefaultRedisScript("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
            return 1
        """.trimIndent(), Long::class.java)
    }
}
