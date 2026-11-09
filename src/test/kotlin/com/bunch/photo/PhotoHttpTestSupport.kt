package com.bunch.photo

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.global.exception.ErrorCode
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * What the photo tests that call the API over HTTP share: a signed-in caller, a bunch with members and the check
 * of an error response. A subclass adds @SpringBootTest and @AutoConfigureMockMvc, which provide [mvc].
 */
abstract class PhotoHttpTestSupport : BunchIntegrationSupport() {
    @Autowired
    protected lateinit var mvc: MockMvc

    @Autowired
    protected lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var tokens: TokenProvider

    @Autowired
    private lateinit var sessions: RefreshTokenRepository

    private val sessionIds = mutableListOf<Pair<String, Long>>()

    @AfterEach
    fun cleanPhotoHttpSessions() {
        sessionIds.forEach { (sessionId, memberId) -> sessions.delete(sessionId, memberId) }
        sessionIds.clear()
    }

    /** The Authorization header of a member with a live session, which is removed after the test. */
    protected fun bearer(memberId: Long): String {
        val pair = tokens.generateToken(memberId, "ROLE_USER")
        val sessionId = tokens.getSessionId(pair.accessToken)
        sessions.save(sessionId, memberId, pair.refreshToken, tokens.getRefreshTokenValiditySeconds())
        sessionIds += sessionId to memberId
        return "Bearer ${pair.accessToken}"
    }

    /** The date change of the photo box (3.4.1) as the member of the app sends it: [body] sets or clears the date. */
    protected fun changePhotoDate(memberId: Long, bunchId: Long, body: String): ResultActions =
        mvc.perform(
            post("/api/v1/bunches/$bunchId/photos/date")
                .header(HttpHeaders.AUTHORIZATION, bearer(memberId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )

    protected fun setPhotoDate(memberId: Long, bunchId: Long, photoIds: List<Long>, date: String): ResultActions =
        changePhotoDate(memberId, bunchId, """{"photoIds":${idList(photoIds)},"capturedDate":"$date"}""")

    protected fun clearPhotoDate(memberId: Long, bunchId: Long, photoIds: List<Long>): ResultActions =
        changePhotoDate(memberId, bunchId, """{"photoIds":${idList(photoIds)},"clearDate":true}""")

    private fun idList(photoIds: List<Long>) = photoIds.joinToString(prefix = "[", postfix = "]")

    /** The owner's member ID and the ID of the bunch the owner created. */
    protected fun bunchWithOwner(): Pair<Long, Long> {
        val ownerId = member()
        return ownerId to create(ownerId).id
    }

    /** A new member who joined the bunch through an invitation of its owner. */
    protected fun joined(ownerId: Long, bunchId: Long): Long {
        val memberId = member()
        join(memberId, issue(ownerId, bunchId))
        return memberId
    }

    /**
     * Inserts a photo straight into the tables, so a test does not need the upload flow. The caller removes the
     * rows it adds: photo_uploaders, photos and the image_assets whose public_id starts with `test-http/`.
     */
    protected fun insertPhoto(
        bunchId: Long,
        date: String?,
        time: String? = null,
        dateSource: String = if (date == null) "UNKNOWN" else "EXIF",
        status: String = "READY",
        uploaders: List<Long> = emptyList()
    ): Long {
        val assetId = jdbc.queryForObject(
            "INSERT INTO image_assets (public_id, url, created_at) VALUES (?, ?, now()) RETURNING id",
            Long::class.java,
            "test-http/${UUID.randomUUID()}",
            "https://res.cloudinary.com/test/image/upload/test-http/photo.jpg"
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

    protected fun MvcResult.data(): JsonNode = mapper.readTree(response.contentAsString).path("data")

    protected fun ResultActions.expectError(error: ErrorCode): ResultActions =
        andExpect(status().`is`(error.status))
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value(error.code))
            .andExpect(jsonPath("$.error.message").value(error.messageKey))
}
