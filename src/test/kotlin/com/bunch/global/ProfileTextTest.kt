package com.bunch.global

import com.bunch.global.common.ProfileText
import com.bunch.global.exception.BusinessException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProfileTextTest {
    @Test
    fun `name trims normalizes and enforces twelve visible Korean English numeric characters`() {
        assertEquals("번치 12", ProfileText.name("  번치 12  "))
        assertEquals("a".repeat(12), ProfileText.name("a".repeat(12)))
        listOf("", "  ", "a".repeat(13), "이름!", "이름😀", "두\n줄", "\u1161".repeat(121)).forEach {
            assertThrows<BusinessException> { ProfileText.name(it) }
        }
    }

    @Test
    fun `introduction counts combined emoji once and preserves internal newlines`() {
        val emoji = "👨‍👩‍👧‍👦"
        assertEquals(emoji.repeat(50), ProfileText.introduction(emoji.repeat(50)))
        assertEquals("첫줄\n둘째줄", ProfileText.introduction(" \n첫줄\n둘째줄\n "))
        assertThrows<BusinessException> { ProfileText.introduction(emoji.repeat(51)) }
        assertThrows<BusinessException> { ProfileText.introduction("\n  \n") }
    }
}
