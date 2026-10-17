package com.bunch.auth.controller

import com.bunch.auth.dto.AuthRequests
import com.bunch.auth.dto.AuthResponses
import com.bunch.auth.service.AuthService
import com.bunch.global.security.TokenPair
import com.bunch.global.common.ApiResponse
import com.bunch.global.oidc.AuthProvider
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import com.bunch.global.security.JwtSessionDetails
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import org.springframework.web.bind.annotation.*

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
    fun logout(authentication: Authentication): ResponseEntity<ApiResponse<Unit>> {
        val userId = authentication.principal as? String ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
        val session = authentication.details as? JwtSessionDetails ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
        authService.logout(userId, session.sessionId)
        return ResponseEntity.ok(ApiResponse.empty(Unit))
    }

    private fun response(pair: TokenPair) = ResponseEntity.ok(ApiResponse.ok(AuthResponses.TokenResponse.from(pair)))
}
