package com.bunch.notification.service

import com.bunch.bunch.domain.BunchRepository
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.notification.domain.Notification
import com.bunch.notification.domain.NotificationType
import com.bunch.notification.dto.NotificationTarget
import com.bunch.notification.dto.PushAlert
import com.bunch.notification.dto.PushAps
import com.bunch.notification.dto.PushPayload
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.regex.Pattern

@Component
class PushPayloadFactory(
    private val bunches: BunchRepository,
    private val mapper: ObjectMapper
) {
    fun forNotification(
        jobId: UUID,
        notification: Notification,
        titleSnapshot: String? = null
    ): PushPayload {
        val title = titleSnapshot ?: run {
            val bunch = bunches.findById(notification.bunchId).orElseThrow {
                BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
            }
            "[${bunch.name}] ${notification.title}"
        }
        return fit(jobId, notification.type, title, notification.body, notification.target)
    }

    fun forDetached(
        jobId: UUID,
        type: NotificationType,
        title: String,
        body: String,
        target: NotificationTarget
    ): PushPayload = fit(jobId, type, title, body, target)

    private fun fit(
        jobId: UUID,
        type: NotificationType,
        title: String,
        body: String,
        target: NotificationTarget
    ): PushPayload {
        val initialBody = if (type == NotificationType.SOCIAL_COMMENT_CREATED) {
            limitGraphemes(body.replace(Regex("[\\r\\n]+"), " "), COMMENT_BODY_LIMIT)
        } else {
            body
        }
        var candidate = payload(jobId, type, title, initialBody, target)
        var currentBody = initialBody
        while (mapper.writeValueAsBytes(candidate).size > MAX_PAYLOAD_BYTES && currentBody.isNotEmpty()) {
            currentBody = shorten(currentBody)
            candidate = payload(jobId, type, title, currentBody, target)
        }
        if (mapper.writeValueAsBytes(candidate).size > MAX_PAYLOAD_BYTES) {
            throw BusinessException(ErrorCode.INVALID_INPUT)
        }
        return candidate
    }

    private fun payload(
        jobId: UUID,
        type: NotificationType,
        title: String,
        body: String,
        target: NotificationTarget
    ) = PushPayload(
        aps = PushAps(alert = PushAlert(title = title, body = body)),
        eventId = jobId,
        type = type,
        target = targetMap(target)
    )

    private fun targetMap(target: NotificationTarget): Map<String, Any?> = buildMap {
        put("kind", target.kind)
        put("bunchId", target.bunchId)
        target.bundleId?.let { put("bundleId", it) }
        target.noticeId?.let { put("noticeId", it) }
        target.targetBunchId?.let { put("targetBunchId", it) }
        target.sourceBunchId?.let { put("sourceBunchId", it) }
    }

    private fun shorten(value: String): String {
        val graphemes = graphemes(value)
        if (graphemes.size <= 1) return ""
        val keep = (graphemes.size - 2).coerceAtLeast(0)
        return graphemes.take(keep).joinToString("") + "…"
    }

    private fun limitGraphemes(value: String, limit: Int): String {
        val graphemes = graphemes(value)
        return if (graphemes.size <= limit) value else graphemes.take(limit).joinToString("") + "…"
    }

    private fun graphemes(value: String): List<String> =
        GRAPHEME.matcher(value).results().map { it.group() }.toList()

    private companion object {
        const val MAX_PAYLOAD_BYTES = 4096
        const val COMMENT_BODY_LIMIT = 50
        val GRAPHEME: Pattern = Pattern.compile("\\X")
    }
}
