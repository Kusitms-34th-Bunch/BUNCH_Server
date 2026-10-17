package com.bunch.bunch.service

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.security.MessageDigest

@Component
class InvitationRateLimiter(
    private val redis: StringRedisTemplate,
    @Value("\${bunch.rate-limit.member-invalid-attempts:10}") private val memberLimit: Int,
    @Value("\${bunch.rate-limit.member-window-seconds:600}") private val memberWindowSeconds: Long,
    @Value("\${bunch.rate-limit.public-attempts:60}") private val publicLimit: Int,
    @Value("\${bunch.rate-limit.public-window-seconds:60}") private val publicWindowSeconds: Long
) {
    init {
        require(memberLimit > 0 && memberWindowSeconds > 0 && publicLimit > 0 && publicWindowSeconds > 0)
    }

    fun publicRequest(remoteAddress: String) {
        val addressHash = MessageDigest.getInstance("SHA-256").digest(remoteAddress.toByteArray())
            .joinToString("") { "%02x".format(it) }
        consume("bunch:invitation:public:$addressHash", publicLimit, publicWindowSeconds)
    }

    fun invalidInvitation(memberId: Long): Nothing {
        consume("bunch:invitation:invalid:$memberId", memberLimit, memberWindowSeconds)
        throw BusinessException(ErrorCode.INVALID_INVITATION)
    }

    private fun consume(key: String, limit: Int, seconds: Long) {
        if (redis.execute(CONSUME, listOf(key), limit.toString(), seconds.toString()) != 1L) {
            throw BusinessException(ErrorCode.RATE_LIMITED)
        }
    }

    companion object {
        private val CONSUME = DefaultRedisScript(
            """local count = tonumber(redis.call('GET', KEYS[1]) or '0')
               if count >= tonumber(ARGV[1]) then return 0 end
               count = redis.call('INCR', KEYS[1])
               if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[2]) end
               return 1""".trimIndent(), Long::class.java
        )
    }
}
