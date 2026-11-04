package com.bunch.photo

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.photo.domain.PhotoListCursor
import com.bunch.photo.domain.PhotoUndatedCursor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import java.util.Base64

class PhotoUndatedCursorTest {
    @Test
    fun `a cursor survives the round trip`() {
        listOf(1L, 5021L, Long.MAX_VALUE).forEach {
            assertThat(PhotoUndatedCursor.decode(PhotoUndatedCursor(it).encode())).isEqualTo(PhotoUndatedCursor(it))
        }
    }

    @Test
    fun `the encoded value is safe to put in a URL`() {
        assertThat(PhotoUndatedCursor(Long.MAX_VALUE).encode()).matches("[A-Za-z0-9_-]+")
    }

    @Test
    fun `the cursor of the photo box list is not a cursor of this list`() {
        val listCursor = PhotoListCursor(LocalDate.of(2026, 10, 19), LocalTime.of(21, 40), 5021).encode()

        assertInvalid(listCursor)
    }

    @Test
    fun `values that were not produced by a cursor are invalid input`() {
        listOf(
            "",
            "not-a-cursor",
            "%%%",
            "a".repeat(500),
            encode("u"),
            encode("u|5|extra"),
            encode("x|5"),
            encode("u|abc"),
            encode("u|0"),
            encode("u|-3"),
            encode("|5")
        ).forEach { assertInvalid(it) }
    }

    private fun assertInvalid(value: String) {
        assertThatThrownBy { PhotoUndatedCursor.decode(value) }
            .describedAs("'$value'")
            .isInstanceOf(BusinessException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.INVALID_INPUT)
    }

    private fun encode(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
}
