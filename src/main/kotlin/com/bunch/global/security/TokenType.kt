package com.bunch.global.security

enum class TokenType(val claim: String) {
    ACCESS("access"),
    REFRESH("refresh")
}
