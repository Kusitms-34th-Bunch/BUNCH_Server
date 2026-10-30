package com.bunch.photo

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.photo.domain.PhotoListCursor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import java.util.Base64

class PhotoListCursorTest {
    @Test
    fun `a cursor survives the round trip with and without a time`() {
        val withTime = PhotoListCursor(LocalDate.of(2026, 10, 19), LocalTime.of(21, 40, 12), 5021)
        val withFraction = PhotoListCursor(LocalDate.of(2026, 1, 2), LocalTime.of(3, 4, 5, 123_456_000), 7)
        val withoutTime = PhotoListCursor(LocalDate.of(2026, 10, 19), null, 9)

        listOf(withTime, withFraction, withoutTime).forEach {
            assertThat(PhotoListCursor.decode(it.encode())).isEqualTo(it)
        }
    }

    @Test
    fun `the encoded value is safe to put in a URL`() {
        val encoded = PhotoListCursor(LocalDate.of(2026, 10, 19), LocalTime.of(21, 40), Long.MAX_VALUE).encode()

        assertThat(encoded).matches("[A-Za-z0-9_-]+")
    }

    @Test
    fun `values that were not produced by a cursor are invalid input`() {
        val invalid = listOf(
            "",
            "not-a-cursor",
            "%%%",
            "a".repeat(500),
            encode("2026-10-19|10:00:00"),
            encode("2026-10-19|10:00:00|5|extra"),
            encode("2026-13-40|10:00:00|5"),
            encode("2026-10-19|25:00:00|5"),
            encode("2026-10-19|10:00:00|abc"),
            encode("2026-10-19|10:00:00|0"),
            encode("2026-10-19|10:00:00|-3"),
            encode("|10:00:00|5")
        )

        invalid.forEach {
            assertThatThrownBy { PhotoListCursor.decode(it) }
                .describedAs("'$it'")
                .isInstanceOf(BusinessException::class.java)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_INPUT)
        }
    }

    private fun encode(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
}
