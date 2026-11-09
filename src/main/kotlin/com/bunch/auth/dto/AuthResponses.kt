package com.bunch.auth.dto

import com.bunch.global.security.TokenPair

object AuthResponses {
    data class DevTokenResponse(val memberId: Long, val accessToken: String)

    data class TokenResponse(val accessToken: String, val refreshToken: String) {
        companion object {
            fun from(pair: TokenPair) = TokenResponse(pair.accessToken, pair.refreshToken)
        }
    }
}
