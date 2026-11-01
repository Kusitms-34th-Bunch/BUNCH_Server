package com.bunch.notification.service

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.member.domain.MemberRepository
import com.bunch.notification.domain.NotificationType
import com.bunch.notification.domain.PushPreferenceRepository
import com.bunch.notification.dto.PushPreferencePatchRequest
import com.bunch.notification.dto.PushPreferenceResponse
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class PushPreferenceService(
    private val members: MemberRepository,
    private val preferences: PushPreferenceRepository,
    private val mapper: ObjectMapper,
    private val clock: Clock
) {
    @Transactional(readOnly = true)
    fun get(memberId: Long): PushPreferenceResponse {
        requireMember(memberId)
        val preference = preferences.findById(memberId).orElse(null)
        return response(preference?.enabled ?: true, preference?.types ?: emptyMap())
    }

    @Transactional
    fun patch(memberId: Long, request: PushPreferencePatchRequest): PushPreferenceResponse {
        requireMember(memberId)
        val patch = request.types.orEmpty()
        if (patch.keys.any { it !in NotificationType.entries.map(NotificationType::name) }) {
            throw BusinessException(ErrorCode.INVALID_INPUT)
        }
        val json = mapper.writeValueAsString(patch)
        preferences.upsert(memberId, request.enabled, json, clock.instant())
        val saved = preferences.findById(memberId).orElseThrow { BusinessException(ErrorCode.INTERNAL_ERROR) }
        return response(saved.enabled, saved.types)
    }

    private fun requireMember(memberId: Long) {
        members.findById(memberId).orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }
            .requireCompleted()
    }

    private fun response(enabled: Boolean, overrides: Map<String, Boolean>): PushPreferenceResponse =
        PushPreferenceResponse(
            enabled = enabled,
            types = NotificationType.entries.associateWith { overrides[it.name] ?: true }
        )
}
