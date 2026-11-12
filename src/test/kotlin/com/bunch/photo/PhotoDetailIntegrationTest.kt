package com.bunch.photo

import com.bunch.global.exception.ErrorCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PhotoDetailIntegrationTest : PhotoHttpTestSupport() {
    @AfterEach
    fun cleanDetailFixtures() {
        deleteInsertedBundles()
        jdbc.update("DELETE FROM photo_uploaders")
        jdbc.update("DELETE FROM photos")
        jdbc.update("UPDATE members SET profile_image_asset_id = NULL WHERE profile_image_asset_id IN " +
            "(SELECT id FROM image_assets WHERE public_id LIKE 'test-detail/%')")
        jdbc.update("DELETE FROM image_assets WHERE public_id LIKE 'test-detail/%'")
    }

    @Test
    fun `returns the stored image the capture date and the uploaders and nothing else`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photoId = photo(
            bunchId,
            "2026-10-19",
            uploaders = listOf(ownerId),
            url = "https://res.cloudinary.com/test/image/upload/test-detail/original.jpg"
        )

        detail(ownerId, bunchId, photoId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.photoId").value(photoId))
            .andExpect(
                jsonPath("$.data.imageUrl")
                    .value("https://res.cloudinary.com/test/image/upload/test-detail/original.jpg")
            )
            .andExpect(jsonPath("$.data.capturedDate").value("2026-10-19"))
            .andExpect(jsonPath("$.data.uploaders.length()").value(1))
            .andExpect(jsonPath("$.data.usedInDraftBundle").value(false))
            // The spec shows no place, no time and no upload date, and the menu is the app's decision.
            .andExpect(jsonPath("$.data.capturedTime").doesNotExist())
            .andExpect(jsonPath("$.data.place").doesNotExist())
            .andExpect(jsonPath("$.data.latitude").doesNotExist())
            .andExpect(jsonPath("$.data.uploadedAt").doesNotExist())
            .andExpect(jsonPath("$.data.canManage").doesNotExist())
            .andExpect(jsonPath("$.data.previousPhotoId").doesNotExist())
            .andExpect(jsonPath("$.data.nextPhotoId").doesNotExist())
            .andExpect(jsonPath("$.data.fileHash").doesNotExist())
            .andExpect(jsonPath("$.data.processingStatus").doesNotExist())
            .andExpect(jsonPath("$.data.dateSource").doesNotExist())
    }

    @Test
    fun `a photo without a date has a null capture date`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photoId = photo(bunchId, null, uploaders = listOf(ownerId))

        detail(ownerId, bunchId, photoId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.capturedDate").isEmpty)
    }

    @Test
    fun `lists the uploaders in the order they uploaded with their current nickname and profile image`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val second = joined(ownerId, bunchId)
        val third = joined(ownerId, bunchId)
        val photoId = photo(bunchId, "2026-10-19", uploaders = listOf(third, ownerId, second))
        jdbc.update("UPDATE members SET nickname = 'renamed' WHERE id = ?", third)
        setProfileImage(ownerId, "https://res.cloudinary.com/test/image/upload/test-detail/owner.jpg")

        detail(second, bunchId, photoId)
            .andExpect(jsonPath("$.data.uploaders.length()").value(3))
            .andExpect(jsonPath("$.data.uploaders[0].memberId").value(third))
            .andExpect(jsonPath("$.data.uploaders[0].nickname").value("renamed"))
            .andExpect(jsonPath("$.data.uploaders[0].imageUrl").isEmpty)
            .andExpect(jsonPath("$.data.uploaders[1].memberId").value(ownerId))
            .andExpect(
                jsonPath("$.data.uploaders[1].imageUrl")
                    .value("https://res.cloudinary.com/test/image/upload/test-detail/owner.jpg")
            )
            .andExpect(jsonPath("$.data.uploaders[2].memberId").value(second))
    }

    @Test
    fun `a photo uploaded together names every uploader to each of them including the viewer`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val friendId = joined(ownerId, bunchId)
        val outsiderViewerId = joined(ownerId, bunchId)
        jdbc.update("UPDATE members SET nickname = 'me' WHERE id = ?", ownerId)
        jdbc.update("UPDATE members SET nickname = 'friend' WHERE id = ?", friendId)
        val photoId = photo(bunchId, "2026-09-29", uploaders = listOf(ownerId, friendId))

        // The app words it as "me and friend" or "friend and ...". The server always sends both, whoever asks.
        listOf(ownerId, friendId, outsiderViewerId).forEach { viewerId ->
            detail(viewerId, bunchId, photoId)
                .andExpect(jsonPath("$.data.uploaders.length()").value(2))
                .andExpect(jsonPath("$.data.uploaders[0].memberId").value(ownerId))
                .andExpect(jsonPath("$.data.uploaders[0].nickname").value("me"))
                .andExpect(jsonPath("$.data.uploaders[1].memberId").value(friendId))
                .andExpect(jsonPath("$.data.uploaders[1].nickname").value("friend"))
        }
    }

    @Test
    fun `a changed profile image shows in the next response`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photoId = photo(bunchId, "2026-10-19", uploaders = listOf(ownerId))

        detail(ownerId, bunchId, photoId).andExpect(jsonPath("$.data.uploaders[0].imageUrl").isEmpty)
        setProfileImage(ownerId, "https://res.cloudinary.com/test/image/upload/test-detail/new.jpg")
        detail(ownerId, bunchId, photoId).andExpect(
            jsonPath("$.data.uploaders[0].imageUrl")
                .value("https://res.cloudinary.com/test/image/upload/test-detail/new.jpg")
        )
    }

    @Test
    fun `a photo that is gone is not found`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photoId = photo(bunchId, "2026-10-19", uploaders = listOf(ownerId))
        jdbc.update("DELETE FROM photo_uploaders")
        jdbc.update("DELETE FROM photos WHERE id = ?", photoId)

        detail(ownerId, bunchId, photoId).expectError(ErrorCode.RESOURCE_NOT_FOUND)
        detail(ownerId, bunchId, Long.MAX_VALUE).expectError(ErrorCode.RESOURCE_NOT_FOUND)
    }

    @Test
    fun `a photo of another bunch is not found`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherBunchId = create(member()).id
        val otherPhoto = photo(otherBunchId, "2026-10-19")

        detail(ownerId, bunchId, otherPhoto).expectError(ErrorCode.RESOURCE_NOT_FOUND)
    }

    @Test
    fun `a photo that is not ready is not found`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val pending = photo(bunchId, "2026-10-19", status = "PENDING")
        val failed = photo(bunchId, "2026-10-19", status = "FAILED")

        detail(ownerId, bunchId, pending).expectError(ErrorCode.RESOURCE_NOT_FOUND)
        detail(ownerId, bunchId, failed).expectError(ErrorCode.RESOURCE_NOT_FOUND)
    }

    @Test
    fun `non members members who left unfinished signups and unknown bunches are rejected`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photoId = photo(bunchId, "2026-10-19", uploaders = listOf(ownerId))
        val outsiderId = member()
        val leftId = joined(ownerId, bunchId)
        jdbc.update("UPDATE bunch_memberships SET status = 'LEFT' WHERE member_id = ?", leftId)
        val unfinishedId = member(completed = false)

        detail(outsiderId, bunchId, photoId).expectError(ErrorCode.FORBIDDEN)
        detail(leftId, bunchId, photoId).expectError(ErrorCode.FORBIDDEN)
        detail(unfinishedId, bunchId, photoId).expectError(ErrorCode.SIGNUP_INCOMPLETE)
        detail(ownerId, Long.MAX_VALUE, photoId).expectError(ErrorCode.RESOURCE_NOT_FOUND)
    }

    @Test
    fun `a photo ID that is not a number is invalid input and the undated path is not read as an ID`() {
        val (ownerId, bunchId) = bunchWithOwner()

        mvc.perform(get("${base(bunchId)}/abc").header(HttpHeaders.AUTHORIZATION, bearer(ownerId)))
            .expectError(ErrorCode.INVALID_INPUT)
        mvc.perform(get("${base(bunchId)}/undated").header(HttpHeaders.AUTHORIZATION, bearer(ownerId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.totalCount").value(0))
    }

    @Test
    fun `a request without a token is unauthorized`() {
        val (_, bunchId) = bunchWithOwner()

        mvc.perform(get("${base(bunchId)}/1")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `tells whether a draft bundle uses the photo`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val inDraft = photo(bunchId, "2026-10-19", uploaders = listOf(ownerId))
        val inPublished = photo(bunchId, "2026-10-19", uploaders = listOf(ownerId))
        val unused = photo(bunchId, "2026-10-19", uploaders = listOf(ownerId))
        insertBundleWith(bunchId, ownerId, "DRAFT", listOf(inDraft))
        insertBundleWith(bunchId, ownerId, "PUBLISHED", listOf(inPublished))

        detail(ownerId, bunchId, inDraft).andExpect(jsonPath("$.data.usedInDraftBundle").value(true))
        detail(ownerId, bunchId, inPublished).andExpect(jsonPath("$.data.usedInDraftBundle").value(false))
        detail(ownerId, bunchId, unused).andExpect(jsonPath("$.data.usedInDraftBundle").value(false))
    }

    private fun detail(memberId: Long, bunchId: Long, photoId: Long): ResultActions =
        mvc.perform(get("${base(bunchId)}/$photoId").header(HttpHeaders.AUTHORIZATION, bearer(memberId)))

    private fun base(bunchId: Long) = "/api/v1/bunches/$bunchId/photos"

    private fun setProfileImage(memberId: Long, url: String) {
        val assetId = jdbc.queryForObject(
            "INSERT INTO image_assets (public_id, url, created_at) VALUES (?, ?, now()) RETURNING id",
            Long::class.java,
            "test-detail/${UUID.randomUUID()}",
            url
        )!!
        jdbc.update("UPDATE members SET profile_image_asset_id = ? WHERE id = ?", assetId, memberId)
    }

    // Inserts straight into the tables, so the detail is tested without the upload flow.
    private fun photo(
        bunchId: Long,
        date: String?,
        status: String = "READY",
        uploaders: List<Long> = emptyList(),
        url: String = "https://res.cloudinary.com/test/image/upload/test-detail/photo.jpg"
    ): Long {
        val assetId = jdbc.queryForObject(
            "INSERT INTO image_assets (public_id, url, created_at) VALUES (?, ?, now()) RETURNING id",
            Long::class.java,
            "test-detail/${UUID.randomUUID()}",
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
            if (date == null) null else "21:40:12",
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

}
