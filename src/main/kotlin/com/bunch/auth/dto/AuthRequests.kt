package com.bunch.auth.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive

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

    data class DevTokenRequest(
        @field:Positive
        val memberId: Long? = null
    )

    data class ReissueRequest(
        @field:NotBlank
        val refreshToken: String
    )
}
