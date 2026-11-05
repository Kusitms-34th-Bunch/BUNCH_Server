package com.bunch.recommendation

import com.bunch.photo.PhotoHttpTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecommendationCandidateSchemaIntegrationTest : PhotoHttpTestSupport() {
    @AfterEach
    fun cleanCandidateFixtures() {
        jdbc.update("DELETE FROM candidate_photos")
        jdbc.update("DELETE FROM bundle_candidates")
        jdbc.update("DELETE FROM recommendation_dirty_dates")
        jdbc.update("DELETE FROM photo_uploaders")
        jdbc.update("DELETE FROM photos")
        jdbc.update("DELETE FROM image_assets WHERE public_id LIKE 'test-http/%'")
    }

    @Test
    fun `a candidate without an end date or a sentence key is stored`() {
        val (_, bunchId) = bunchWithOwner()

        val candidateId = candidate(bunchId, end = null, key = null)

        assertThat(count("bundle_candidates", "id = $candidateId")).isEqualTo(1)
    }

    @Test
    fun `deleting a bunch deletes its candidates their photos and its changed dates`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photoId = insertPhoto(bunchId, "2026-10-19", "20:00:00", uploaders = listOf(ownerId))
        val candidateId = candidate(bunchId)
        candidatePhoto(candidateId, photoId, position = 1)
        jdbc.update(
            "INSERT INTO recommendation_dirty_dates (bunch_id, captured_date, changed_at) " +
                "VALUES (?, DATE '2026-10-19', now())",
            bunchId
        )

        jdbc.update("DELETE FROM bunches WHERE id = ?", bunchId)

        assertThat(count("bundle_candidates", "id = $candidateId")).isZero()
        assertThat(count("candidate_photos", "candidate_id = $candidateId")).isZero()
        assertThat(count("recommendation_dirty_dates", "bunch_id = $bunchId")).isZero()
    }

    @Test
    fun `deleting a photo deletes only its row of a candidate`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val gone = insertPhoto(bunchId, "2026-10-19", "20:00:00", uploaders = listOf(ownerId))
        val stays = insertPhoto(bunchId, "2026-10-19", "20:30:00", uploaders = listOf(ownerId))
        val candidateId = candidate(bunchId)
        candidatePhoto(candidateId, gone, position = 1)
        candidatePhoto(candidateId, stays)

        jdbc.update("DELETE FROM photos WHERE id = ?", gone)

        assertThat(count("bundle_candidates", "id = $candidateId")).isEqualTo(1)
        assertThat(count("candidate_photos", "candidate_id = $candidateId")).isEqualTo(1)
        assertThat(count("candidate_photos", "photo_id = $stays")).isEqualTo(1)
    }

    @Test
    fun `a photo is in a candidate only once`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photoId = insertPhoto(bunchId, "2026-10-19", "20:00:00", uploaders = listOf(ownerId))
        val candidateId = candidate(bunchId)
        candidatePhoto(candidateId, photoId)

        assertRejected { candidatePhoto(candidateId, photoId) }
    }

    @Test
    fun `a photo has no position or a position from 1 to 10`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val candidateId = candidate(bunchId)
        fun photo() = insertPhoto(bunchId, "2026-10-19", "20:00:00", uploaders = listOf(ownerId))

        candidatePhoto(candidateId, photo(), position = 1)
        candidatePhoto(candidateId, photo(), position = 10)
        candidatePhoto(candidateId, photo())

        assertRejected { candidatePhoto(candidateId, photo(), position = 0) }
        assertRejected { candidatePhoto(candidateId, photo(), position = 11) }
        assertThat(count("candidate_photos", "candidate_id = $candidateId")).isEqualTo(3)
    }

    @Test
    fun `an end date must be after the start date`() {
        val (_, bunchId) = bunchWithOwner()

        candidate(bunchId, start = "2026-10-19", end = "2026-10-20")

        assertRejected { candidate(bunchId, start = "2026-10-19", end = "2026-10-19") }
        assertRejected { candidate(bunchId, start = "2026-10-19", end = "2026-10-18") }
    }

    @Test
    fun `a title is not blank has no line break and is at most 60 characters`() {
        val (_, bunchId) = bunchWithOwner()

        candidate(bunchId, title = "가".repeat(60))

        assertRejected { candidate(bunchId, title = "   ") }
        assertRejected { candidate(bunchId, title = "불타는\n토요일 밤") }
        assertRejected { candidate(bunchId, title = "가".repeat(61)) }
    }

    @Test
    fun `a candidate cannot be updated before it was created`() {
        val (_, bunchId) = bunchWithOwner()

        assertRejected {
            jdbc.update(
                """INSERT INTO bundle_candidates (bunch_id, start_date, title, last_photo_added_at,
                    created_at, updated_at)
                    VALUES (?, DATE '2026-10-19', '불타는 토요일 밤', now(), now(), now() - interval '1 second')""",
                bunchId
            )
        }
    }

    private fun assertRejected(insert: () -> Unit) {
        assertThatThrownBy(insert).isInstanceOf(DataIntegrityViolationException::class.java)
    }

    private fun candidate(
        bunchId: Long,
        start: String = "2026-10-19",
        end: String? = null,
        title: String = "불타는 토요일 밤",
        key: String? = "default.weekend_evening.1"
    ): Long = jdbc.queryForObject(
        """INSERT INTO bundle_candidates (bunch_id, start_date, end_date, title, title_sentence_key,
            last_photo_added_at, created_at, updated_at)
            VALUES (?, CAST(? AS date), CAST(? AS date), ?, ?, now(), now(), now()) RETURNING id""",
        Long::class.java,
        bunchId,
        start,
        end,
        title,
        key
    )!!

    private fun candidatePhoto(candidateId: Long, photoId: Long, position: Int? = null) {
        jdbc.update(
            "INSERT INTO candidate_photos (candidate_id, photo_id, position) VALUES (?, ?, ?)",
            candidateId,
            photoId,
            position
        )
    }

    private fun count(table: String, where: String): Int =
        jdbc.queryForObject("SELECT count(*) FROM $table WHERE $where", Int::class.java)!!
}
