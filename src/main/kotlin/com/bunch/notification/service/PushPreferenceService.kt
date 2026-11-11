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
        return PushPreferenceResponse(
            enabled = preference?.enabled ?: true,
            categories = categoryValues(preference?.categories.orEmpty())
        )
    }

    // The member lock serializes patches of one account, so different toggles sent at once are all kept.
    // Only the values of the request change. The app push toggle and the categories do not depend on each other:
    // switching every category off leaves the app push toggle on, and turning it on gives back the saved values.
    @Transactional
    fun patch(memberId: Long, request: PushPreferencePatchRequest): PushPreferenceResponse {
        members.findLockedById(memberId)?.requireCompleted()
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
        val changes = request.categories.orEmpty()
        if (changes.keys.any { key -> PushCategory.entries.none { it.name == key } }) {
            throw BusinessException(ErrorCode.INVALID_INPUT)
        }
        val preference = preferences.findById(memberId).orElse(null)
        val enabled = request.enabled ?: preference?.enabled ?: true
        val categories = categoryValues(preference?.categories.orEmpty() + changes)
        val stored = categories.mapKeys { it.key.name }
        val now = clock.instant()
        if (preference == null) {
            preferences.save(PushPreference(memberId, enabled, stored, now))
        } else {
            preference.enabled = enabled
            preference.categories = stored
            preference.updatedAt = now
        }
        return PushPreferenceResponse(enabled, categories)
    }

    // A category without a saved value is on.
    private fun categoryValues(stored: Map<String, Boolean>): Map<PushCategory, Boolean> =
        PushCategory.entries.associateWith { stored[it.name] ?: true }
}
