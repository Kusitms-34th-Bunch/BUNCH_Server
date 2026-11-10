package com.bunch.notification.service

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.member.domain.MemberRepository
import com.bunch.notification.domain.push.PushCategory
import com.bunch.notification.domain.push.PushPreference
import com.bunch.notification.domain.push.PushPreferenceRepository
import com.bunch.notification.dto.PushPreferencePatchRequest
import com.bunch.notification.dto.PushPreferenceResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class PushPreferenceService(
    private val members: MemberRepository,
    private val preferences: PushPreferenceRepository,
    private val clock: Clock
) {
    @Transactional(readOnly = true)
    fun get(memberId: Long): PushPreferenceResponse {
        members.findById(memberId)
            .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }
            .requireCompleted()
        val preference = preferences.findById(memberId).orElse(null)
        return response(preference?.enabled ?: true, preference?.categories.orEmpty())
    }

    // The member lock serializes patches of one account, so different toggles sent at once are all kept.
    @Transactional
    fun patch(memberId: Long, request: PushPreferencePatchRequest): PushPreferenceResponse {
        members.findLockedById(memberId)?.requireCompleted()
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
        val changes = request.categories.orEmpty()
        if (changes.keys.any { key -> PushCategory.entries.none { it.name == key } }) {
            throw BusinessException(ErrorCode.INVALID_INPUT)
        }
        val preference = preferences.findById(memberId).orElse(null)
        var enabled = request.enabled ?: preference?.enabled ?: true
        var categories = PushCategory.entries.associate {
            it.name to (changes[it.name] ?: preference?.categories?.get(it.name) ?: true)
        }
        if (categories.values.none { it }) {
            if (changes.isNotEmpty()) {
                // Every toggle was switched off, so the app push toggle goes off with them (7.3.3).
                enabled = false
            } else if (request.enabled == true) {
                // Turning push back on never leaves it on with nothing to receive.
                categories = categories.mapValues { true }
            }
        }
        val now = clock.instant()
        if (preference == null) {
            preferences.save(PushPreference(memberId, enabled, categories, now))
        } else {
            preference.enabled = enabled
            preference.categories = categories
            preference.updatedAt = now
        }
        return response(enabled, categories)
    }

    private fun response(enabled: Boolean, stored: Map<String, Boolean>): PushPreferenceResponse =
        PushPreferenceResponse(
            enabled = enabled,
            categories = PushCategory.entries.associateWith { stored[it.name] ?: true }
        )
}
