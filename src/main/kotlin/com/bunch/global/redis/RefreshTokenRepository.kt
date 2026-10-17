package com.bunch.global.redis

interface RefreshTokenRepository {
    fun save(sessionId: String, userId: Long, refreshToken: String, expiresInSeconds: Long)

    fun isActive(sessionId: String, userId: Long): Boolean

    fun delete(sessionId: String, userId: Long): Boolean

    fun compareAndRotate(
        sessionId: String,
        userId: Long,
        expectedToken: String,
        newToken: String,
        expiresInSeconds: Long
    ): Boolean
}
