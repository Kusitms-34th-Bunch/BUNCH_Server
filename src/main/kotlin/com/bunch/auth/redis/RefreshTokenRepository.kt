package com.bunch.auth.redis

interface RefreshTokenRepository {
    fun save(userId: Long, refreshToken: String, expiresInSeconds: Long)
    fun get(userId: Long): String?
    fun delete(userId: Long)
    fun compareAndRotate(userId: Long, expectedToken: String, newToken: String, expiresInSeconds: Long): Boolean
}
