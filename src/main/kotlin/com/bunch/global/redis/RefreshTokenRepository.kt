package com.bunch.global.redis

interface RefreshTokenRepository {
    fun save(sessionId: String, userId: Long, refreshToken: String, expiresInSeconds: Long)

    fun isActive(sessionId: String, userId: Long): Boolean

    fun delete(sessionId: String, userId: Long): Boolean

    // Every session of the account on every device, for the withdrawal (7.6.4). Returns how many were removed.
    fun deleteAll(userId: Long): Int

    fun compareAndRotate(
        sessionId: String,
        userId: Long,
        expectedToken: String,
        newToken: String,
        expiresInSeconds: Long
    ): Boolean
}
