package com.bunch.member.service

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.member.domain.MemberRepository
import com.bunch.member.dto.SupportChannelResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.net.URI

@Service
class SupportChannelService(
    private val members: MemberRepository,
    @Value("\${support.open-chat-url:}")
    openChatUrl: String
) {
    // A missing or invalid setting disables the channel, never the application startup.
    private val url: String? = validated(openChatUrl)

    fun current(memberId: Long): SupportChannelResponse {
        members.findById(memberId)
            .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }
            .requireCompleted()
        return SupportChannelResponse(url ?: throw BusinessException(ErrorCode.SUPPORT_CHANNEL_UNAVAILABLE))
    }

    private fun validated(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty() || value.length > MAX_URL_LENGTH) return null
        if (value.any { it.isWhitespace() || it.isISOControl() }) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val secure = uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null
        return value.takeIf { secure }
    }

    private companion object {
        const val MAX_URL_LENGTH = 2048
    }
}
