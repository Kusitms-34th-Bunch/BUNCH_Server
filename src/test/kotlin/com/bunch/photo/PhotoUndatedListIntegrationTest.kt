package com.bunch.photo

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.global.exception.ErrorCode
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import com.bunch.photo.domain.PhotoListCursor
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PhotoUndatedListIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var tokens: TokenProvider

    @Autowired
    private lateinit var sessions: RefreshTokenRepository

    private val sessionIds = mutableListOf<Pair<String, Long>>()

    @AfterEach
    fun cleanUndatedFixtures() {
        sessionIds.forEach { (sessionId, memberId) -> sessions.delete(sessionId, memberId) }
        sessionIds.clear()
        jdbc.update("DELETE FROM photo_uploaders")
        jdbc.update("DELETE FROM photos")
        jdbc.update("DELETE FROM image_assets WHERE public_id LIKE 'test-undated/%'")
    }

    @Test
    fun `lists only the ready photos without a date newest upload first`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val first = photo(bunchId, null)
        val second = photo(bunchId, null)
        val third = photo(bunchId, null)

        assertThat(listIds(ownerId, bunchId)).containsExactly(third, second, first)
    }

    @Test
    fun `excludes dated photos photos whose date the user set photos not ready and photos of other bunches`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherBunchId = create(member()).id
        val shown = photo(bunchId, null)
        photo(bunchId, "2026-10-19", dateSource = "EXIF")
        photo(bunchId, "2026-10-19", dateSource = "USER")
        photo(bunchId, null, status = "PENDING")
        photo(bunchId, null, status = "FAILED")
        photo(otherBunchId, null)

        assertThat(listIds(ownerId, bunchId)).containsExactly(shown)
    }

    @Test
    fun `an empty area returns an empty list a zero total and no next cursor`() {
        val (ownerId, bunchId) = bunchWithOwner()
        photo(bunchId, "2026-10-19")

        undated(ownerId, bunchId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.totalCount").value(0))
            .andExpect(jsonPath("$.data.photos.length()").value(0))
            .andExpect(jsonPath("$.data.nextCursor").isEmpty)
    }

    @Test
    fun `following the cursor visits every photo once whatever the page size`() {
        val (ownerId, bunchId) = bunchWithOwner()
        repeat(9) { photo(bunchId, null) }
        val everything = listIds(ownerId, bunchId, mapOf("size" to "100"))
        assertThat(everything).hasSize(9).doesNotHaveDuplicates()

        (1..10).forEach { size ->
            val visited = mutableListOf<Long>()
            var cursor: String? = null
            var pages = 0
            do {
                // A cursor that does not move forward must fail here instead of looping for ever.
                assertThat(++pages).describedAs("size=$size").isLessThanOrEqualTo(everything.size + 1)
                val page = page(ownerId, bunchId, size, cursor)
                assertThat(page.ids.size).isLessThanOrEqualTo(size)
                visited += page.ids
                cursor = page.nextCursor
            } while (cursor != null)
            assertThat(visited).describedAs("size=$size").containsExactlyElementsOf(everything)
        }
    }

    @Test
    fun `a page that holds exactly the remaining photos has no next cursor`() {
        val (ownerId, bunchId) = bunchWithOwner()
        repeat(3) { photo(bunchId, null) }

        val page = page(ownerId, bunchId, size = 3, cursor = null)

        assertThat(page.ids).hasSize(3)
        assertThat(page.nextCursor).isNull()
    }

    @Test
    fun `the default page holds 60 photos and the rest follows`() {
        val (ownerId, bunchId) = bunchWithOwner()
        seedPhotos(bunchId, 61)

        val first = undated(ownerId, bunchId).andExpect(status().isOk).andReturn().data()
        assertThat(first.path("photos")).hasSize(60)
        assertThat(first.path("totalCount").asLong()).isEqualTo(61)
        val cursor = first.path("nextCursor").asText()
        assertThat(cursor).isNotBlank()

        val second = undated(ownerId, bunchId, mapOf("cursor" to cursor)).andReturn().data()
        assertThat(second.path("photos")).hasSize(1)
        assertThat(second.path("nextCursor").isNull).isTrue()
    }

    @Test
    fun `filters by uploader and shows a co-uploaded photo to every uploader`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherId = joined(ownerId, bunchId)
        val mine = photo(bunchId, null, uploaders = listOf(ownerId))
        val theirs = photo(bunchId, null, uploaders = listOf(otherId))
        val together = photo(bunchId, null, uploaders = listOf(ownerId, otherId))

        assertThat(listIds(ownerId, bunchId)).containsExactly(together, theirs, mine)
        assertThat(listIds(ownerId, bunchId, mapOf("memberId" to ownerId.toString()))).containsExactly(together, mine)
        assertThat(listIds(ownerId, bunchId, mapOf("memberId" to otherId.toString()))).containsExactly(together, theirs)

        // The filter does not hide the other uploader of a shared photo.
        undated(ownerId, bunchId, mapOf("memberId" to ownerId.toString()))
            .andExpect(jsonPath("$.data.photos[0].photoId").value(together))
            .andExpect(jsonPath("$.data.photos[0].uploaderIds.length()").value(2))
    }

    @Test
    fun `the filter pages through its own photos`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherId = joined(ownerId, bunchId)
        val mine = (1..5).map { photo(bunchId, null, uploaders = listOf(ownerId)) }
        repeat(4) { photo(bunchId, null, uploaders = listOf(otherId)) }

        val visited = mutableListOf<Long>()
        var cursor: String? = null
        do {
            val params = mutableMapOf("memberId" to ownerId.toString(), "size" to "2")
            if (cursor != null) params["cursor"] = cursor
            val data = undated(ownerId, bunchId, params).andExpect(status().isOk).andReturn().data()
            visited += data.path("photos").map { it.path("photoId").asLong() }
            cursor = data.path("nextCursor").takeUnless { it.isNull }?.asText()
        } while (cursor != null)

        assertThat(visited).containsExactlyElementsOf(mine.reversed())
    }

    @Test
    fun `lists the uploaders of a photo in the order they uploaded`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val second = joined(ownerId, bunchId)
        val third = joined(ownerId, bunchId)
        photo(bunchId, null, uploaders = listOf(third, ownerId, second))

        undated(ownerId, bunchId)
            .andExpect(jsonPath("$.data.photos[0].uploaderIds[0]").value(third))
            .andExpect(jsonPath("$.data.photos[0].uploaderIds[1]").value(ownerId))
            .andExpect(jsonPath("$.data.photos[0].uploaderIds[2]").value(second))
    }

    @Test
    fun `the total follows the filter and does not depend on the page size`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherId = joined(ownerId, bunchId)
        photo(bunchId, null, uploaders = listOf(ownerId))
        photo(bunchId, null, uploaders = listOf(ownerId))
        photo(bunchId, null, uploaders = listOf(otherId))
        photo(bunchId, "2026-10-19", uploaders = listOf(ownerId))
        photo(bunchId, null, status = "PENDING", uploaders = listOf(ownerId))

        undated(ownerId, bunchId, mapOf("size" to "1"))
            .andExpect(jsonPath("$.data.photos.length()").value(1))
            .andExpect(jsonPath("$.data.totalCount").value(3))
        undated(ownerId, bunchId, mapOf("memberId" to ownerId.toString(), "size" to "1"))
            .andExpect(jsonPath("$.data.photos.length()").value(1))
            .andExpect(jsonPath("$.data.totalCount").value(2))
        undated(ownerId, bunchId, mapOf("memberId" to otherId.toString()))
            .andExpect(jsonPath("$.data.totalCount").value(1))
    }

    @Test
    fun `the thumbnail is the square transformation of the stored image and nothing else is returned`() {
        val (ownerId, bunchId) = bunchWithOwner()
        photo(
            bunchId,
            null,
            uploaders = listOf(ownerId),
            url = "https://res.cloudinary.com/test/image/upload/test-undated/abc.jpg"
        )

        undated(ownerId, bunchId)
            .andExpect(
                jsonPath("$.data.photos[0].thumbnailUrl")
                    .value("https://res.cloudinary.com/test/image/upload/c_fill,w_300,h_300/test-undated/abc.jpg")
            )
            .andExpect(jsonPath("$.data.photos[0].capturedDate").doesNotExist())
            .andExpect(jsonPath("$.data.photos[0].capturedTime").doesNotExist())
            .andExpect(jsonPath("$.data.photos[0].imageUrl").doesNotExist())
            .andExpect(jsonPath("$.data.photos[0].fileHash").doesNotExist())
            .andExpect(jsonPath("$.data.photos[0].processingStatus").doesNotExist())
            .andExpect(jsonPath("$.data.photos[0].dateSource").doesNotExist())
    }

    @Test
    fun `rejects a member ID that is not an active member of the bunch`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val outsiderId = member()
        val leftId = joined(ownerId, bunchId)
        jdbc.update("UPDATE bunch_memberships SET status = 'LEFT' WHERE member_id = ?", leftId)

        undated(ownerId, bunchId, mapOf("memberId" to outsiderId.toString())).expectError(ErrorCode.INVALID_INPUT)
        undated(ownerId, bunchId, mapOf("memberId" to leftId.toString())).expectError(ErrorCode.INVALID_INPUT)
        undated(ownerId, bunchId, mapOf("memberId" to "abc")).expectError(ErrorCode.INVALID_INPUT)
    }

    @Test
    fun `rejects a cursor that cannot be read including one of the photo box list and a size outside 1 to 100`() {
        val (ownerId, bunchId) = bunchWithOwner()
        photo(bunchId, null)
        val listCursor = PhotoListCursor(LocalDate.of(2026, 10, 19), null, 7).encode()

        undated(ownerId, bunchId, mapOf("cursor" to "not-a-cursor")).expectError(ErrorCode.INVALID_INPUT)
        undated(ownerId, bunchId, mapOf("cursor" to listCursor)).expectError(ErrorCode.INVALID_INPUT)
        undated(ownerId, bunchId, mapOf("size" to "0")).expectError(ErrorCode.INVALID_INPUT)
        undated(ownerId, bunchId, mapOf("size" to "101")).expectError(ErrorCode.INVALID_INPUT)
        undated(ownerId, bunchId, mapOf("size" to "-5")).expectError(ErrorCode.INVALID_INPUT)
        undated(ownerId, bunchId, mapOf("size" to "many")).expectError(ErrorCode.INVALID_INPUT)
        undated(ownerId, bunchId, mapOf("size" to "100")).andExpect(status().isOk)
        undated(ownerId, bunchId, mapOf("size" to "1")).andExpect(status().isOk)
    }

    @Test
    fun `non members members who left unfinished signups and unknown bunches are rejected`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val outsiderId = member()
        val leftId = joined(ownerId, bunchId)
        jdbc.update("UPDATE bunch_memberships SET status = 'LEFT' WHERE member_id = ?", leftId)
        val unfinishedId = member(completed = false)

        undated(outsiderId, bunchId).expectError(ErrorCode.FORBIDDEN)
        undated(leftId, bunchId).expectError(ErrorCode.FORBIDDEN)
        undated(unfinishedId, bunchId).expectError(ErrorCode.SIGNUP_INCOMPLETE)
        undated(ownerId, Long.MAX_VALUE).expectError(ErrorCode.RESOURCE_NOT_FOUND)
    }

    @Test
    fun `a request without a token is unauthorized`() {
        val (_, bunchId) = bunchWithOwner()

        mvc.perform(get(path(bunchId))).andExpect(status().isUnauthorized)
    }

    private data class Page(val ids: List<Long>, val nextCursor: String?)

    private fun page(memberId: Long, bunchId: Long, size: Int, cursor: String?): Page {
        val params = mutableMapOf("size" to size.toString())
        if (cursor != null) params["cursor"] = cursor
        val data = undated(memberId, bunchId, params).andExpect(status().isOk).andReturn().data()
        val next = data.path("nextCursor")
        return Page(data.path("photos").map { it.path("photoId").asLong() }, if (next.isNull) null else next.asText())
    }

    private fun listIds(memberId: Long, bunchId: Long, params: Map<String, String> = emptyMap()): List<Long> =
        undated(memberId, bunchId, params).andExpect(status().isOk).andReturn().data()
            .path("photos").map { it.path("photoId").asLong() }

    private fun undated(memberId: Long, bunchId: Long, params: Map<String, String> = emptyMap()): ResultActions {
        val request = get(path(bunchId)).header(HttpHeaders.AUTHORIZATION, bearer(memberId))
        params.forEach { (name, value) -> request.param(name, value) }
        return mvc.perform(request)
    }

    private fun path(bunchId: Long) = "/api/v1/bunches/$bunchId/photos/undated"

    private fun MvcResult.data(): JsonNode = mapper.readTree(response.contentAsString).path("data")

    private fun bunchWithOwner(): Pair<Long, Long> {
        val ownerId = member()
        return ownerId to create(ownerId).id
    }

    private fun joined(ownerId: Long, bunchId: Long): Long {
        val memberId = member()
        join(memberId, issue(ownerId, bunchId))
        return memberId
    }

    // Inserts straight into the tables, so the list is tested without the upload flow.
    private fun photo(
        bunchId: Long,
        date: String?,
        status: String = "READY",
        dateSource: String = if (date == null) "UNKNOWN" else "EXIF",
        uploaders: List<Long> = emptyList(),
        url: String = "https://res.cloudinary.com/test/image/upload/test-undated/photo.jpg"
    ): Long {
        val assetId = jdbc.queryForObject(
            "INSERT INTO image_assets (public_id, url, created_at) VALUES (?, ?, now()) RETURNING id",
            Long::class.java,
            "test-undated/${UUID.randomUUID()}",
            url
        )!!
        val photoId = jdbc.queryForObject(
            """INSERT INTO photos (bunch_id, image_asset_id, file_hash, processing_status,
                captured_date, date_source, created_at)
                VALUES (?, ?, encode(sha256(convert_to(?, 'UTF8')), 'hex'), ?,
                CAST(? AS date), ?, now()) RETURNING id""",
            Long::class.java,
            bunchId,
            assetId,
            UUID.randomUUID().toString(),
            status,
            date,
            dateSource
        )!!
        uploaders.forEach {
            jdbc.update(
                """INSERT INTO photo_uploaders (photo_id, member_id, upload_batch_id, uploaded_at)
                    VALUES (?, ?, CAST(? AS uuid), now())""",
                photoId,
                it,
                UUID.randomUUID().toString()
            )
        }
        return photoId
    }

    private fun seedPhotos(bunchId: Long, count: Int) {
        val assetId = jdbc.queryForObject(
            "INSERT INTO image_assets (public_id, url, created_at) VALUES (?, ?, now()) RETURNING id",
            Long::class.java,
            "test-undated/${UUID.randomUUID()}",
            "https://res.cloudinary.com/test/image/upload/test-undated/seed.jpg"
        )!!
        jdbc.update(
            """INSERT INTO photos (bunch_id, image_asset_id, file_hash, processing_status,
                date_source, created_at)
                SELECT ?, ?, encode(sha256(convert_to(CAST(? AS text) || g::text, 'UTF8')), 'hex'), 'READY',
                'UNKNOWN', now() FROM generate_series(1, ?) AS g""",
            bunchId,
            assetId,
            UUID.randomUUID().toString(),
            count
        )
    }

    private fun bearer(memberId: Long): String {
        val pair = tokens.generateToken(memberId, "ROLE_USER")
        val sessionId = tokens.getSessionId(pair.accessToken)
        sessions.save(sessionId, memberId, pair.refreshToken, tokens.getRefreshTokenValiditySeconds())
        sessionIds += sessionId to memberId
        return "Bearer ${pair.accessToken}"
    }

    private fun ResultActions.expectError(error: ErrorCode): ResultActions =
        andExpect(status().`is`(error.status))
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value(error.code))
            .andExpect(jsonPath("$.error.message").value(error.messageKey))
}
