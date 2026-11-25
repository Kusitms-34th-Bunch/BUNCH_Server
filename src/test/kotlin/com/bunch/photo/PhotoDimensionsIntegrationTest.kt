package com.bunch.photo

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PhotoDimensionsIntegrationTest : PhotoHttpTestSupport() {
    @AfterEach
    fun cleanDimensionFixtures() {
        jdbc.update("DELETE FROM photo_uploaders")
        jdbc.update("DELETE FROM photos")
        jdbc.update("DELETE FROM image_assets WHERE object_key LIKE 'photos/test-http-%/original.jpg'")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `dated and undated lists and detail include legacy null and new display dimensions`(dated: Boolean) {
        val (ownerId, bunchId) = bunchWithOwner()
        val date = if (dated) "2026-10-19" else null
        val legacy = insertPhoto(bunchId, date, uploaders = listOf(ownerId))
        val current = insertPhoto(bunchId, date, uploaders = listOf(ownerId))
        jdbc.update("UPDATE photos SET width = 800, height = 1200 WHERE id = ?", current)
        val authorization = bearer(ownerId)
        val base = "/api/v1/bunches/$bunchId/photos"
        val listPath = if (dated) base else "$base/undated"

        // Both the unfiltered and member-filtered projection queries carry the dimensions.
        for (filtered in listOf(false, true)) {
            val request = get(listPath).header(HttpHeaders.AUTHORIZATION, authorization)
            if (filtered) request.param("memberId", ownerId.toString())
            val data = mvc.perform(request).andExpect(status().isOk).andReturn().data()
            assertThat(data.fieldNames().asSequence().toSet())
                .containsExactlyInAnyOrder("totalCount", "photos", "nextCursor")
            val items = data.path("photos").associateBy { it.path("photoId").asLong() }
            assertThat(items.keys).containsExactlyInAnyOrder(legacy, current)
            assertDimensions(items.getValue(legacy), null, null)
            assertDimensions(items.getValue(current), 800, 1200)
            val fields = mutableListOf("photoId", "thumbnailUrl", "uploaderIds", "usedInDraftBundle", "width", "height")
            if (dated) fields += listOf("capturedDate", "capturedTime")
            items.values.forEach { item ->
                assertThat(item.fieldNames().asSequence().toSet()).containsExactlyInAnyOrderElementsOf(fields)
                assertThat(item.path("thumbnailUrl").asText()).contains("thumbnail.jpg")
                assertThat(item.path("uploaderIds").map { it.asLong() }).containsExactly(ownerId)
                assertThat(item.path("usedInDraftBundle").asBoolean()).isFalse()
                if (dated) assertThat(item.path("capturedDate").asText()).isEqualTo(date)
            }
        }

        for (photoId in listOf(legacy, current)) {
            val detail = mvc.perform(get("$base/$photoId").header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isOk).andReturn().data()
            assertThat(detail.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder(
                "photoId", "imageUrl", "capturedDate", "uploaders", "usedInDraftBundle", "width", "height"
            )
            assertThat(detail.path("photoId").asLong()).isEqualTo(photoId)
            assertThat(detail.path("imageUrl").asText()).contains("original.jpg")
            assertDimensions(detail, if (photoId == current) 800 else null, if (photoId == current) 1200 else null)
        }
    }

    private fun assertDimensions(photo: JsonNode, width: Int?, height: Int?) {
        for ((name, expected) in listOf("width" to width, "height" to height)) {
            assertThat(photo.has(name)).describedAs("$name must be serialized, including null").isTrue()
            if (expected == null) {
                assertThat(photo.path(name).isNull).isTrue()
            } else {
                assertThat(photo.path(name).isInt).isTrue()
                assertThat(photo.path(name).asInt()).isEqualTo(expected)
            }
        }
    }
}
