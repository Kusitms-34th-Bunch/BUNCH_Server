package com.bunch.bundle

import com.bunch.place.domain.PlaceDetails
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PlaceDetailsTest {
    @Test
    fun `canonicalizes provider without changing external ID case`() {
        val place = PlaceDetails.of(" KaKaO_1-test ", " AbC ", " 서울 ", " 주소 ")
        assertThat(place.provider).isEqualTo("kakao_1-test")
        assertThat(place.externalId).isEqualTo("AbC")
        assertThat(place.name).isEqualTo("서울")
        assertThat(place.address).isEqualTo("주소")
    }

    @Test
    fun `validates shared limits by graphemes and handles family emoji at the boundary`() {
        val family = "👨‍👩‍👧‍👦"
        assertThat(PlaceDetails.of("a".repeat(32), "a".repeat(128), family.repeat(200), family.repeat(500)).name)
            .isEqualTo(family.repeat(200))
        val invalid = listOf(
            listOf("a".repeat(33), "id", "name", "address"),
            listOf("잘못된공급자", "id", "name", "address"),
            listOf("bad.provider", "id", "name", "address"),
            listOf("provider", "a".repeat(129), "name", "address"),
            listOf("provider", "id", family.repeat(201), "address"),
            listOf("provider", "id", "name", family.repeat(501))
        )
        invalid.forEach { args -> assertThatThrownBy { PlaceDetails.of(args[0], args[1], args[2], args[3]) }
            .isInstanceOf(IllegalArgumentException::class.java) }
    }

    @Test
    fun `rejects blank fields and database null characters`() {
        (0..3).forEach { index ->
            listOf(" \n\t ", "\u0000").forEach { value ->
                val input = mutableListOf("provider", "id", "name", "address")
                input[index] = value
                assertThatThrownBy { PlaceDetails.of(input[0], input[1], input[2], input[3]) }
                    .isInstanceOf(IllegalArgumentException::class.java)
            }
        }
    }
}
