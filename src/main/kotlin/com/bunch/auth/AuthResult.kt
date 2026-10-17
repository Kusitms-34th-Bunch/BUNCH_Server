package com.bunch.auth

object AuthResult {
    data class TokenPair(val accessToken: String, val refreshToken: String)
}
