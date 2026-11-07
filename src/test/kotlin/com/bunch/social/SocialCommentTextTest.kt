package com.bunch.social

import com.bunch.global.exception.BusinessException
import com.bunch.social.domain.SocialCommentText
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SocialCommentTextTest {
    @Test
    fun `trim preserves inner newlines and counts visible graphemes`() {
        assertThat(SocialCommentText.content("  첫줄\n둘째줄  ")).isEqualTo("첫줄\n둘째줄")
        listOf("가", "e\u0301", "👨‍👩‍👧‍👦", "🇰🇷").forEach { grapheme ->
            assertThat(SocialCommentText.content(grapheme.repeat(200))).isEqualTo(grapheme.repeat(200))
            assertThatThrownBy { SocialCommentText.content(grapheme.repeat(201)) }
                .isInstanceOf(BusinessException::class.java)
        }
    }

    @Test
    fun `blank content is rejected`() {
        listOf("", "   ", "\r\n\t", "\u2003").forEach {
            assertThatThrownBy { SocialCommentText.content(it) }.isInstanceOf(BusinessException::class.java)
        }
    }
}
