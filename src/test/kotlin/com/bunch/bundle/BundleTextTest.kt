package com.bunch.bundle

import com.bunch.bundle.domain.BundleText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BundleTextTest {
    @Test
    fun `title trims normalizes and counts graphemes`() {
        assertEquals("여행", BundleText.title("  여행  "))
        assertEquals("é", BundleText.title("e\u0301"))

        val family = "👨‍👩‍👧‍👦"
        assertEquals(family.repeat(40), BundleText.title(family.repeat(40)))
        assertThrows<IllegalArgumentException> { BundleText.title(family.repeat(41)) }
    }

    @Test
    fun `title rejects line breaks`() {
        assertThrows<IllegalArgumentException> { BundleText.title("첫 줄\n둘째 줄") }
        assertThrows<IllegalArgumentException> { BundleText.title("첫 줄\r둘째 줄") }
    }

    @Test
    fun `comments and captions allow line breaks and enforce grapheme limits`() {
        assertEquals("  첫 줄\n둘째 줄  ", BundleText.comment("  첫 줄\n둘째 줄  "))
        assertEquals("é", BundleText.caption("e\u0301"))

        assertEquals("a".repeat(400), BundleText.comment("a".repeat(400)))
        assertEquals("a".repeat(200), BundleText.caption("a".repeat(200)))
        assertThrows<IllegalArgumentException> { BundleText.comment("a".repeat(401)) }
        assertThrows<IllegalArgumentException> { BundleText.caption("a".repeat(201)) }
    }
}
