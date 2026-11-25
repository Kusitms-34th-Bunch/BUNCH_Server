package com.bunch.auth.dto

import jakarta.validation.constraints.NotBlank

object AuthRequests {
    data class NonceLoginRequest(
        @field:NotBlank
        val idToken: String,
        @field:NotBlank
        val nonce: String
    )

    data class GoogleLoginRequest(
        @field:NotBlank
        val idToken: String
    )

    data class ReissueRequest(
        @field:NotBlank
        val refreshToken: String
    )
}
