package com.bunch.photo

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.time.ZoneId

/**
 * The photos that were given a date in one request are one batch (3.4.1, 4.2.7 rule 3). The batch is told apart from
 * the upload batch of the photos, so a photo can have both.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PhotoDateBatchIntegrationTest : PhotoHttpTestSupport() {
    @AfterEach
    fun cleanDateBatchFixtures() {
        jdbc.update("DELETE FROM photo_uploaders")
        jdbc.update("DELETE FROM photos")
        jdbc.update("DELETE FROM image_assets WHERE public_id LIKE 'test-http/%'")
    }

    @Test
    fun `photos that change in one request share one batch id`() {
        val (owner, bunchId) = bunchWithOwner()
        val photoIds = listOf(
            insertPhoto(bunchId, "2026-09-28", "10:00:00", uploaders = listOf(owner)),
            insertPhoto(bunchId, null, uploaders = listOf(owner)),
            insertPhoto(bunchId, "2026-09-20", uploaders = listOf(owner))
        )

        setPhotoDate(owner, bunchId, photoIds, "2026-10-03").andExpect(status().isOk)

        val batchIds = photoIds.map { batchIdOf(it) }
        assertThat(batchIds).doesNotContainNull()
        assertThat(batchIds.toSet()).hasSize(1)
    }

    @Test
    fun `another request gives another batch id`() {
        val (owner, bunchId) = bunchWithOwner()
        val first = insertPhoto(bunchId, null, uploaders = listOf(owner))
        val second = insertPhoto(bunchId, null, uploaders = listOf(owner))

        setPhotoDate(owner, bunchId, listOf(first), "2026-10-03").andExpect(status().isOk)
        setPhotoDate(owner, bunchId, listOf(second), "2026-10-03").andExpect(status().isOk)

        assertThat(batchIdOf(first)).isNotNull()
        assertThat(batchIdOf(second)).isNotNull().isNotEqualTo(batchIdOf(first))
    }

    @Test
    fun `a photo that is dated again gets the batch id of the new request`() {
        val (owner, bunchId) = bunchWithOwner()
        val photoId = insertPhoto(bunchId, null, uploaders = listOf(owner))
        setPhotoDate(owner, bunchId, listOf(photoId), "2026-10-03").andExpect(status().isOk)
        val before = batchIdOf(photoId)

        setPhotoDate(owner, bunchId, listOf(photoId), "2026-10-04").andExpect(status().isOk)

        assertThat(batchIdOf(photoId)).isNotNull().isNotEqualTo(before)
    }

    @Test
    fun `clearing the date removes the batch id`() {
        val (owner, bunchId) = bunchWithOwner()
        val photoIds = listOf(
            insertPhoto(bunchId, null, uploaders = listOf(owner)),
            insertPhoto(bunchId, null, uploaders = listOf(owner))
        )
        setPhotoDate(owner, bunchId, photoIds, "2026-10-03").andExpect(status().isOk)
        assertThat(photoIds.map { batchIdOf(it) }).doesNotContainNull()

        clearPhotoDate(owner, bunchId, photoIds).andExpect(status().isOk)

        assertThat(photoIds.map { batchIdOf(it) }).containsOnlyNulls()
    }

    @Test
    fun `a photo that is not in the request keeps no batch id`() {
        val (owner, bunchId) = bunchWithOwner()
        val changed = insertPhoto(bunchId, null, uploaders = listOf(owner))
        val untouched = insertPhoto(bunchId, "2026-09-28", "12:00:00", uploaders = listOf(owner))

        setPhotoDate(owner, bunchId, listOf(changed), "2026-10-03").andExpect(status().isOk)

        assertThat(batchIdOf(untouched)).isNull()
    }

    @Test
    fun `a request that is refused stores no batch id`() {
        val (owner, bunchId) = bunchWithOwner()
        val photoId = insertPhoto(bunchId, null, uploaders = listOf(owner))
        val tomorrow = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1)

        setPhotoDate(owner, bunchId, listOf(photoId), tomorrow.toString()).andExpect(status().isBadRequest)

        assertThat(batchIdOf(photoId)).isNull()
    }

    @Test
    fun `a photo the user did not date cannot carry a batch id`() {
        val (owner, bunchId) = bunchWithOwner()
        val photoId = insertPhoto(bunchId, "2026-09-28", "10:00:00", uploaders = listOf(owner))

        assertThatThrownBy {
            jdbc.update("UPDATE photos SET date_batch_id = gen_random_uuid() WHERE id = ?", photoId)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    private fun batchIdOf(photoId: Long): String? =
        jdbc.queryForObject("SELECT CAST(date_batch_id AS text) FROM photos WHERE id = ?", String::class.java, photoId)
}
