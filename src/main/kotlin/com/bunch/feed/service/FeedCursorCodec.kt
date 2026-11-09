package com.bunch.feed.service

import com.bunch.feed.domain.FeedSegment
import com.bunch.feed.dto.FeedCursor
import com.bunch.feed.dto.FeedViewer
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.Base64

@Component
class FeedCursorCodec(private val mapper: ObjectMapper) {
    fun encode(cursor: FeedCursor): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(mapper.writeValueAsBytes(cursor))

    fun decode(
        token: String,
        viewer: FeedViewer,
        membershipContext: String,
        followingContext: String,
        now: Instant
    ): FeedCursor {
        val cursor = try {
            if (token.isBlank() || token.length > 4096) invalid()
            val bytes = Base64.getUrlDecoder().decode(token)
            mapper.readValue(bytes, FeedCursor::class.java)
        } catch (_: Exception) {
            invalid()
        }
        if (cursor.version != 1 || cursor.viewerId != viewer.memberId ||
            cursor.anchorTime < EARLIEST || cursor.anchorTime > now ||
            cursor.bundleCount <= 0 || cursor.bundleCount > Long.MAX_VALUE - 50 ||
            (cursor.lastAdId != null && cursor.lastAdId <= 0) ||
            (cursor.lastTime == null) != (cursor.lastId == null) ||
            (cursor.lastTime != null && (cursor.lastTime < EARLIEST || cursor.lastTime > cursor.anchorTime)) ||
            (cursor.lastId != null && cursor.lastId <= 0) ||
            !HASH.matches(cursor.membershipContext) || !HASH.matches(cursor.followingContext) ||
            (cursor.currentBunchId != null && cursor.currentBunchId <= 0) ||
            (cursor.segment == FeedSegment.FOLLOWING &&
                (cursor.currentBunchId == null || cursor.lastTime == null || !cursor.followingSeen ||
                    cursor.lastTime < cursor.anchorTime.minusSeconds(14 * 86_400L)))
        ) invalid()
        if (cursor.currentBunchId != viewer.currentBunchId || cursor.membershipContext != membershipContext ||
            cursor.followingContext != followingContext
        ) throw BusinessException(ErrorCode.FEED_CURSOR_CONTEXT_CHANGED)
        return cursor
    }

    private fun invalid(): Nothing = throw BusinessException(ErrorCode.INVALID_INPUT)

    private companion object {
        val EARLIEST: Instant = Instant.parse("0001-01-15T00:00:00Z")
        val HASH = Regex("[0-9a-f]{64}")
    }
}
