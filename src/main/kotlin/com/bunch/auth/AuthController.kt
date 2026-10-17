package com.bunch.auth

import com.bunch.global.common.ApiResponse
import com.bunch.member.AuthProvider
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

object AuthRequests {
    data class NonceLoginRequest(@field:NotBlank val idToken: String, @field:NotBlank val nonce: String)
    data class GoogleLoginRequest(@field:NotBlank val idToken: String)
    data class ReissueRequest(@field:NotBlank val refreshToken: String)
}

object AuthResponses {
    data class TokenResponse(val accessToken: String, val refreshToken: String) {
        companion object {
            fun from(pair: AuthResult.TokenPair) = TokenResponse(pair.accessToken, pair.refreshToken)
        }
    }
}

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(private val authService: AuthService) {
    @PostMapping("/apple/login")
    fun apple(@Valid @RequestBody request: AuthRequests.NonceLoginRequest) =
        response(authService.login(AuthProvider.APPLE, request.idToken, request.nonce))

    @PostMapping("/google/login")
    fun google(@Valid @RequestBody request: AuthRequests.GoogleLoginRequest) =
        response(authService.login(AuthProvider.GOOGLE, request.idToken))

    @PostMapping("/kakao/login")
    fun kakao(@Valid @RequestBody request: AuthRequests.NonceLoginRequest) =
        response(authService.login(AuthProvider.KAKAO, request.idToken, request.nonce))

    @PostMapping("/reissue")
    fun reissue(@Valid @RequestBody request: AuthRequests.ReissueRequest) = response(authService.reissue(request.refreshToken))

    @PostMapping("/logout")
    fun logout(@AuthenticationPrincipal userId: String): ResponseEntity<ApiResponse<Unit>> {
        authService.logout(userId)
        return ResponseEntity.ok(ApiResponse.empty(Unit))
    }

    private fun response(pair: AuthResult.TokenPair) = ResponseEntity.ok(ApiResponse.ok(AuthResponses.TokenResponse.from(pair)))
}
