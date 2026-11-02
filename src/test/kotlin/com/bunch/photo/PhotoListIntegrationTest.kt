package com.bunch.photo

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.global.exception.ErrorCode
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
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
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PhotoListIntegrationTest : BunchIntegrationSupport() {
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
    fun cleanPhotoListFixtures() {
        sessionIds.forEach { (sessionId, memberId) -> sessions.delete(sessionId, memberId) }
        sessionIds.clear()
        jdbc.update("DELETE FROM photo_uploaders")
        jdbc.update("DELETE FROM photos")
        jdbc.update("DELETE FROM image_assets WHERE public_id LIKE 'test-list/%'")
    }

    @Test
    fun `sorts the newest date first then the later time and puts photos without a time last in the day`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val oldDay = photo(bunchId, "2026-10-18", "09:00:00")
        val morning = photo(bunchId, "2026-10-19", "08:00:00")
        val evening = photo(bunchId, "2026-10-19", "21:00:00")
        val noTime = photo(bunchId, "2026-10-19")
        val sameEvening = photo(bunchId, "2026-10-19", "21:00:00")

        assertThat(listIds(ownerId, bunchId)).containsExactly(evening, sameEvening, morning, noTime, oldDay)
    }

    @Test
    fun `excludes photos without a date photos that are not ready and photos of other bunches`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherOwnerId = member()
        val otherBunchId = create(otherOwnerId).id
        val shown = photo(bunchId, "2026-10-19", "10:00:00")
        photo(bunchId, null)
        photo(bunchId, "2026-10-19", "11:00:00", status = "PENDING")
        photo(bunchId, "2026-10-19", "12:00:00", status = "FAILED")
        photo(otherBunchId, "2026-10-19", "13:00:00")

        assertThat(listIds(ownerId, bunchId)).containsExactly(shown)
    }

    @Test
    fun `an empty photo box returns an empty list a zero total and no next cursor`() {
        val (ownerId, bunchId) = bunchWithOwner()

        list(ownerId, bunchId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.totalCount").value(0))
            .andExpect(jsonPath("$.data.photos.length()").value(0))
            .andExpect(jsonPath("$.data.nextCursor").doesNotExist())
    }

    @Test
    fun `following the cursor visits every photo once whatever the page size`() {
        val (ownerId, bunchId) = bunchWithOwner()
        // Ties on the time, photos without a time and several days make the page edges land on every case.
        photo(bunchId, "2026-10-17", "09:00:00")
        photo(bunchId, "2026-10-19", "21:00:00")
        photo(bunchId, "2026-10-19")
        photo(bunchId, "2026-10-19", "21:00:00")
        photo(bunchId, "2026-10-18")
        photo(bunchId, "2026-10-19")
        photo(bunchId, "2026-10-19", "08:30:00")
        photo(bunchId, "2026-10-18", "23:59:59")
        photo(bunchId, "2026-10-19", "21:00:00")
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
        repeat(3) { photo(bunchId, "2026-10-19", "1$it:00:00") }

        val page = page(ownerId, bunchId, size = 3, cursor = null)

        assertThat(page.ids).hasSize(3)
        assertThat(page.nextCursor).isNull()
    }

    @Test
    fun `the default page holds 60 photos and the rest follows`() {
        val (ownerId, bunchId) = bunchWithOwner()
        seedPhotos(bunchId, 61, "2026-10-19")

        val first = list(ownerId, bunchId).andExpect(status().isOk).andReturn().data()
        assertThat(first.path("photos")).hasSize(60)
        val cursor = first.path("nextCursor").asText()
        assertThat(cursor).isNotBlank()
        assertThat(first.path("totalCount").asLong()).isEqualTo(61)

        val second = list(ownerId, bunchId, mapOf("cursor" to cursor)).andReturn().data()
        assertThat(second.path("photos")).hasSize(1)
        assertThat(second.path("nextCursor").isNull).isTrue()
    }

    @Test
    fun `filters by uploader and shows a co-uploaded photo to every uploader`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherId = joined(ownerId, bunchId)
        val mine = photo(bunchId, "2026-10-19", "10:00:00", uploaders = listOf(ownerId))
        val theirs = photo(bunchId, "2026-10-19", "11:00:00", uploaders = listOf(otherId))
        val together = photo(bunchId, "2026-10-19", "12:00:00", uploaders = listOf(ownerId, otherId))

        assertThat(listIds(ownerId, bunchId)).containsExactly(together, theirs, mine)
        assertThat(listIds(ownerId, bunchId, mapOf("memberId" to ownerId.toString()))).containsExactly(together, mine)
        assertThat(listIds(ownerId, bunchId, mapOf("memberId" to otherId.toString()))).containsExactly(together, theirs)

        // The filter does not hide the other uploader of a shared photo.
        list(ownerId, bunchId, mapOf("memberId" to ownerId.toString()))
            .andExpect(jsonPath("$.data.photos[0].photoId").value(together))
            .andExpect(jsonPath("$.data.photos[0].uploaderIds.length()").value(2))
    }

    @Test
    fun `lists the uploaders of a photo in the order they uploaded`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val second = joined(ownerId, bunchId)
        val third = joined(ownerId, bunchId)
        photo(bunchId, "2026-10-19", "10:00:00", uploaders = listOf(third, ownerId, second))

        list(ownerId, bunchId)
            .andExpect(jsonPath("$.data.photos[0].uploaderIds[0]").value(third))
            .andExpect(jsonPath("$.data.photos[0].uploaderIds[1]").value(ownerId))
            .andExpect(jsonPath("$.data.photos[0].uploaderIds[2]").value(second))
    }

    @Test
    fun `the total counts the whole bunch whatever the filter the date or the status`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherId = joined(ownerId, bunchId)
        photo(bunchId, "2026-10-19", "10:00:00", uploaders = listOf(ownerId))
        photo(bunchId, "2026-10-19", "11:00:00", uploaders = listOf(ownerId))
        photo(bunchId, "2026-10-19", "12:00:00", uploaders = listOf(otherId))
        photo(bunchId, null, uploaders = listOf(otherId))
        photo(bunchId, "2026-10-19", "13:00:00", status = "PENDING", uploaders = listOf(otherId))

        list(ownerId, bunchId, mapOf("memberId" to ownerId.toString()))
            .andExpect(jsonPath("$.data.photos.length()").value(2))
            .andExpect(jsonPath("$.data.totalCount").value(5))
    }

    @Test
    fun `the thumbnail is the square transformation of the stored image`() {
        val (ownerId, bunchId) = bunchWithOwner()
        photo(
            bunchId,
            "2026-10-19",
            "10:00:00",
            url = "https://res.cloudinary.com/test/image/upload/test-list/abc.jpg"
        )

        list(ownerId, bunchId).andExpect(
            jsonPath("$.data.photos[0].thumbnailUrl")
                .value("https://res.cloudinary.com/test/image/upload/c_fill,w_300,h_300/test-list/abc.jpg")
        )
    }

    @Test
    fun `returns the capture date and time and no other photo details`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val noTime = photo(bunchId, "2026-10-19", uploaders = listOf(ownerId))
        val timed = photo(bunchId, "2026-10-19", "21:40:12", uploaders = listOf(ownerId))

        list(ownerId, bunchId)
            .andExpect(jsonPath("$.data.photos[0].photoId").value(timed))
            .andExpect(jsonPath("$.data.photos[0].capturedDate").value("2026-10-19"))
            .andExpect(jsonPath("$.data.photos[0].capturedTime").value("21:40:12"))
            .andExpect(jsonPath("$.data.photos[1].photoId").value(noTime))
            .andExpect(jsonPath("$.data.photos[1].capturedTime").doesNotExist())
            .andExpect(jsonPath("$.data.photos[0].fileHash").doesNotExist())
            .andExpect(jsonPath("$.data.photos[0].processingStatus").doesNotExist())
            .andExpect(jsonPath("$.data.photos[0].dateSource").doesNotExist())
            .andExpect(jsonPath("$.data.photos[0].imageUrl").doesNotExist())
            .andExpect(jsonPath("$.data.photos[0].latitude").doesNotExist())
    }

    @Test
    fun `rejects a member ID that is not an active member of the bunch`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val outsiderId = member()
        val leftId = joined(ownerId, bunchId)
        jdbc.update("UPDATE bunch_memberships SET status = 'LEFT' WHERE member_id = ?", leftId)

        list(ownerId, bunchId, mapOf("memberId" to outsiderId.toString())).expectError(ErrorCode.INVALID_INPUT)
        list(ownerId, bunchId, mapOf("memberId" to leftId.toString())).expectError(ErrorCode.INVALID_INPUT)
        list(ownerId, bunchId, mapOf("memberId" to "abc")).expectError(ErrorCode.INVALID_INPUT)
    }

    @Test
    fun `rejects a cursor that cannot be read and a size outside 1 to 100`() {
        val (ownerId, bunchId) = bunchWithOwner()
        photo(bunchId, "2026-10-19", "10:00:00")

        list(ownerId, bunchId, mapOf("cursor" to "not-a-cursor")).expectError(ErrorCode.INVALID_INPUT)
        list(ownerId, bunchId, mapOf("size" to "0")).expectError(ErrorCode.INVALID_INPUT)
        list(ownerId, bunchId, mapOf("size" to "101")).expectError(ErrorCode.INVALID_INPUT)
        list(ownerId, bunchId, mapOf("size" to "-5")).expectError(ErrorCode.INVALID_INPUT)
        list(ownerId, bunchId, mapOf("size" to "many")).expectError(ErrorCode.INVALID_INPUT)
        list(ownerId, bunchId, mapOf("size" to "100")).andExpect(status().isOk)
        list(ownerId, bunchId, mapOf("size" to "1")).andExpect(status().isOk)
    }

    @Test
    fun `non members members who left unfinished signups and unknown bunches are rejected`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val outsiderId = member()
        val leftId = joined(ownerId, bunchId)
        jdbc.update("UPDATE bunch_memberships SET status = 'LEFT' WHERE member_id = ?", leftId)
        val unfinishedId = member(completed = false)

        list(outsiderId, bunchId).expectError(ErrorCode.FORBIDDEN)
        list(leftId, bunchId).expectError(ErrorCode.FORBIDDEN)
        list(unfinishedId, bunchId).expectError(ErrorCode.SIGNUP_INCOMPLETE)
        list(ownerId, Long.MAX_VALUE).expectError(ErrorCode.RESOURCE_NOT_FOUND)
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
        val data = list(memberId, bunchId, params).andExpect(status().isOk).andReturn().data()
        val next = data.path("nextCursor")
        return Page(data.path("photos").map { it.path("photoId").asLong() }, if (next.isNull) null else next.asText())
    }

    private fun listIds(memberId: Long, bunchId: Long, params: Map<String, String> = emptyMap()): List<Long> =
        list(memberId, bunchId, params).andExpect(status().isOk).andReturn().data()
            .path("photos").map { it.path("photoId").asLong() }

    private fun list(memberId: Long, bunchId: Long, params: Map<String, String> = emptyMap()): ResultActions {
        val request = get(path(bunchId)).header(HttpHeaders.AUTHORIZATION, bearer(memberId))
        params.forEach { (name, value) -> request.param(name, value) }
        return mvc.perform(request)
    }

    private fun path(bunchId: Long) = "/api/v1/bunches/$bunchId/photos"

    private fun org.springframework.test.web.servlet.MvcResult.data(): JsonNode =
        mapper.readTree(response.contentAsString).path("data")

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
        time: String? = null,
        status: String = "READY",
        uploaders: List<Long> = emptyList(),
        url: String = "https://res.cloudinary.com/test/image/upload/test-list/photo.jpg"
    ): Long {
        val assetId = jdbc.queryForObject(
            "INSERT INTO image_assets (public_id, url, created_at) VALUES (?, ?, now()) RETURNING id",
            Long::class.java,
            "test-list/${UUID.randomUUID()}",
            url
        )!!
        val photoId = jdbc.queryForObject(
            """INSERT INTO photos (bunch_id, image_asset_id, file_hash, processing_status,
                captured_date, captured_time, date_source, created_at)
                VALUES (?, ?, encode(sha256(convert_to(?, 'UTF8')), 'hex'), ?,
                CAST(? AS date), CAST(? AS time), ?, now()) RETURNING id""",
            Long::class.java,
            bunchId,
            assetId,
            UUID.randomUUID().toString(),
            status,
            date,
            time,
            if (date == null) "UNKNOWN" else "EXIF"
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

    private fun seedPhotos(bunchId: Long, count: Int, date: String) {
        val assetId = jdbc.queryForObject(
            "INSERT INTO image_assets (public_id, url, created_at) VALUES (?, ?, now()) RETURNING id",
            Long::class.java,
            "test-list/${UUID.randomUUID()}",
            "https://res.cloudinary.com/test/image/upload/test-list/seed.jpg"
        )!!
        jdbc.update(
            """INSERT INTO photos (bunch_id, image_asset_id, file_hash, processing_status,
                captured_date, date_source, created_at)
                SELECT ?, ?, encode(sha256(convert_to(CAST(? AS text) || g::text, 'UTF8')), 'hex'), 'READY',
                CAST(? AS date), 'EXIF', now() FROM generate_series(1, ?) AS g""",
            bunchId,
            assetId,
            UUID.randomUUID().toString(),
            date,
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
