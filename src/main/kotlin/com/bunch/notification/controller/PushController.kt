package com.bunch.notification.controller

import com.bunch.global.common.ApiResponse
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.security.JwtSessionDetails
import com.bunch.notification.dto.PushDeviceRegistrationRequest
import com.bunch.notification.dto.PushPreferencePatchRequest
import com.bunch.notification.service.PushDeviceService
import com.bunch.notification.service.PushPreferenceService
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/members/me")
class PushController(
    private val devices: PushDeviceService,
    private val preferences: PushPreferenceService
) {
    @PutMapping("/push-devices/{installationId}")
    fun registerDevice(
        authentication: Authentication,
        @PathVariable installationId: UUID,
        @RequestBody request: PushDeviceRegistrationRequest
    ) = ApiResponse.ok(devices.register(memberId(authentication), sessionId(authentication), installationId, request))

    @DeleteMapping("/push-devices/{installationId}")
    fun unregisterDevice(
        authentication: Authentication,
        @PathVariable installationId: UUID
    ): ApiResponse<Unit> {
        devices.unregister(memberId(authentication), sessionId(authentication), installationId)
        return ApiResponse.empty(Unit)
    }

    @GetMapping("/push-preferences")
    fun getPreferences(authentication: Authentication) =
        ApiResponse.ok(preferences.get(memberId(authentication)))

    @PatchMapping("/push-preferences")
    fun patchPreferences(
        authentication: Authentication,
        @RequestBody request: PushPreferencePatchRequest
    ) = ApiResponse.ok(preferences.patch(memberId(authentication), request))

    private fun memberId(authentication: Authentication): Long =
        (authentication.principal as? String)?.toLongOrNull()
            ?: throw BusinessException(ErrorCode.UNAUTHORIZED)

    private fun sessionId(authentication: Authentication): UUID =
        (authentication.details as? JwtSessionDetails)?.sessionId?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
        } ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
}
