package com.bunch.feed

import com.bunch.feed.domain.FeedSegment
import com.bunch.feed.dto.FeedCursor
import com.bunch.feed.dto.FeedViewer
import com.bunch.feed.service.FeedCursorCodec
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import java.time.Instant

class FeedCursorCodecTest {
    private val codec = FeedCursorCodec(jacksonObjectMapper().findAndRegisterModules())
    private val now = Instant.parse("2026-11-07T12:34:56.123456Z")
    private val viewer = FeedViewer(1, 2, setOf(2, 3))
    private val membership = "a".repeat(64)
    private val following = "b".repeat(64)
    private val cursor = FeedCursor(
        anchorTime = now,
        segment = FeedSegment.FOLLOWING,
        lastTime = now.minusSeconds(1),
        lastId = 4,
        viewerId = viewer.memberId,
        currentBunchId = viewer.currentBunchId,
        membershipContext = membership,
        followingContext = following,
        followingSeen = true,
        bundleCount = 20,
        lastAdId = 9
    )

    @Test
    fun `round trip preserves publication precision and pagination and ad context`() {
        assertThat(decode(codec.encode(cursor))).isEqualTo(cursor)
        assertThat(codec.encode(cursor)).doesNotContain("anchorTime", "FOLLOWING")
    }

    @Test
    fun `public boundary has no publication tuple and zero membership is allowed`() {
        val value = cursor.copy(
            segment = FeedSegment.PUBLIC, lastTime = null, lastId = null, currentBunchId = null
        )
        assertThat(codec.decode(codec.encode(value), viewer.copy(currentBunchId = null), membership, following, now))
            .isEqualTo(value)
    }

    @Test
    fun `current selection membership and follow changes request a fresh feed`() {
        val token = codec.encode(cursor)
        listOf(
            { codec.decode(token, viewer.copy(currentBunchId = 3), membership, following, now) },
            { codec.decode(token, viewer, "c".repeat(64), following, now) },
            { codec.decode(token, viewer, membership, "d".repeat(64), now) }
        ).forEach { expect(ErrorCode.FEED_CURSOR_CONTEXT_CHANGED, it) }
    }

    @Test
    fun `another viewer malformed base64 oversized payload and invalid json are rejected`() {
        expect(ErrorCode.INVALID_INPUT) {
            codec.decode(codec.encode(cursor), viewer.copy(memberId = 8), membership, following, now)
        }
        listOf("", " ", "@@", "e30", "a".repeat(4097)).forEach { token ->
            expect(ErrorCode.INVALID_INPUT) { decode(token) }
        }
    }

    @Test
    fun `invalid version dates tuple counts identifiers and fingerprints are rejected`() {
        listOf(
            cursor.copy(version = 2),
            cursor.copy(anchorTime = now.plusNanos(1)),
            cursor.copy(anchorTime = Instant.MIN),
            cursor.copy(lastTime = now.plusSeconds(1)),
            cursor.copy(lastTime = now.minusSeconds(15 * 86_400L)),
            cursor.copy(lastTime = null),
            cursor.copy(lastId = 0),
            cursor.copy(bundleCount = 0),
            cursor.copy(bundleCount = Long.MAX_VALUE),
            cursor.copy(lastAdId = -1),
            cursor.copy(currentBunchId = -1),
            cursor.copy(followingSeen = false),
            cursor.copy(membershipContext = "bad"),
            cursor.copy(followingContext = "bad")
        ).forEach { value -> expect(ErrorCode.INVALID_INPUT) { decode(codec.encode(value)) } }
    }

    private fun decode(token: String) = codec.decode(token, viewer, membership, following, now)

    private fun expect(code: ErrorCode, action: () -> Any?) {
        val error = catchThrowable { action() }
        assertThat(error).isInstanceOf(BusinessException::class.java)
        assertThat((error as BusinessException).errorCode).isEqualTo(code)
    }
}
