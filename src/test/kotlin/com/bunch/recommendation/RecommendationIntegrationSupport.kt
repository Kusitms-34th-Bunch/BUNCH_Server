package com.bunch.recommendation

import com.bunch.photo.PhotoHttpTestSupport
import com.bunch.recommendation.service.RecommendationService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.RowMapper
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * What the tests of the recalculation share: a gathering of photos, the call of the recalculation and a look at the
 * stored candidates. A subclass adds @SpringBootTest and @AutoConfigureMockMvc.
 */
abstract class RecommendationIntegrationSupport : PhotoHttpTestSupport() {
    protected val gatheringDay = "2026-10-19"

    @Autowired
    protected lateinit var recommendation: RecommendationService

    @AfterEach
    fun cleanRecalculationFixtures() {
        jdbc.update("DELETE FROM candidate_photos")
        jdbc.update("DELETE FROM bundle_candidates")
        jdbc.update("DELETE FROM recommendation_dirty_dates")
        jdbc.update("DELETE FROM bundle_photos")
        jdbc.update("DELETE FROM bundles")
        jdbc.update("DELETE FROM photo_uploaders")
        jdbc.update("DELETE FROM photos")
        jdbc.update("DELETE FROM image_assets WHERE public_id LIKE 'test-http/%'")
    }

    // The photos of one gathering, one every [stepMinutes], uploaded by the given members in turn.
    protected fun shoot(
        bunchId: Long,
        date: String,
        from: String,
        count: Int,
        uploaders: List<Long>,
        stepMinutes: Long = 10
    ): List<Long> = (0 until count).map { index ->
        val time = LocalTime.parse(from).plusMinutes(stepMinutes * index)
        insertPhoto(bunchId, date, time.toString(), uploaders = listOf(uploaders[index % uploaders.size]))
    }

    // A bunch with a gathering of photos on [date], uploaded by [memberCount] members in turn, ready to be calculated.
    protected fun bunchWithGathering(date: String = gatheringDay, count: Int = 8, memberCount: Int = 2): Gathering {
        val (ownerId, bunchId) = bunchWithOwner()
        val members = listOf(ownerId) + List(memberCount - 1) { joined(ownerId, bunchId) }

        return Gathering(bunchId, members, shoot(bunchId, date, "19:00", count, members))
    }

    protected fun recalculate(bunchId: Long, vararg dates: String) =
        recommendation.recalculate(bunchId, dates.map { LocalDate.parse(it) }.toSet())

    // A moment some days before now, cut to milliseconds so the database gives back the same value.
    protected fun daysAgo(days: Long): Instant =
        Instant.now().minus(Duration.ofDays(days)).truncatedTo(ChronoUnit.MILLIS)

    // An instant given as a Korean local time, the way the days of a recommended bundle are counted.
    protected fun kst(local: String): Instant = Instant.parse("${local}Z").minusSeconds(9 * 3600L)

    protected fun setLastPhotoAddedAt(candidateId: Long, at: Instant) {
        jdbc.update(
            "UPDATE bundle_candidates SET last_photo_added_at = ? WHERE id = ?",
            java.sql.Timestamp.from(at),
            candidateId
        )
    }

    protected fun setAddedAt(photoId: Long, addedAt: String) {
        jdbc.update("UPDATE photos SET created_at = CAST(? AS timestamptz) WHERE id = ?", addedAt, photoId)
    }

    protected fun latestAddedAt(bunchId: Long): Instant = jdbc.queryForObject(
        "SELECT max(created_at) FROM photos WHERE bunch_id = ?",
        java.sql.Timestamp::class.java,
        bunchId
    )!!.toInstant()

    protected fun candidates(bunchId: Long): List<CandidateRow> = jdbc.query(
        """SELECT id, title, title_sentence_key, start_date, end_date, last_photo_added_at
            FROM bundle_candidates WHERE bunch_id = ? ORDER BY start_date, id""",
        RowMapper { rs, _ ->
            CandidateRow(
                id = rs.getLong("id"),
                title = rs.getString("title"),
                sentenceKey = rs.getString("title_sentence_key"),
                startDate = rs.getDate("start_date").toLocalDate(),
                endDate = rs.getDate("end_date")?.toLocalDate(),
                lastPhotoAddedAt = rs.getTimestamp("last_photo_added_at").toInstant()
            )
        },
        bunchId
    )

    protected fun photosOf(candidateId: Long): List<Pair<Long, Int?>> = jdbc.query(
        "SELECT photo_id, position FROM candidate_photos WHERE candidate_id = ? ORDER BY id",
        RowMapper { rs, _ -> rs.getLong("photo_id") to rs.getInt("position").takeUnless { rs.wasNull() } },
        candidateId
    )

    protected fun bundleWith(bunchId: Long, creatorId: Long, status: String, vararg photoIds: Long): Long {
        val bundleId = jdbc.queryForObject(
            """INSERT INTO bundles (bunch_id, created_by, status, created_at, updated_at, published_at)
                VALUES (?, ?, ?, now(), now(), CASE WHEN ? = 'PUBLISHED' THEN now() END) RETURNING id""",
            Long::class.java,
            bunchId,
            creatorId,
            status,
            status
        )!!
        photoIds.forEachIndexed { position, photoId ->
            jdbc.update(
                """INSERT INTO bundle_photos (bundle_id, photo_id, image_asset_id, position, added_by, added_at)
                    SELECT ?, id, image_asset_id, ?, ?, now() FROM photos WHERE id = ?""",
                bundleId,
                position,
                creatorId,
                photoId
            )
        }
        return bundleId
    }

    protected fun assertTwoDifferentTitles(bunchId: Long) {
        val titles = candidates(bunchId).map { it.title }
        assertThat(titles).hasSize(2)
        assertThat(titles.toSet()).hasSize(2)
    }

    protected fun deleteBundle(bundleId: Long) {
        jdbc.update("DELETE FROM bundle_photos WHERE bundle_id = ?", bundleId)
        jdbc.update("DELETE FROM bundles WHERE id = ?", bundleId)
    }

    protected data class Gathering(val bunchId: Long, val members: List<Long>, val photoIds: List<Long>)

    protected data class CandidateRow(
        val id: Long,
        val title: String,
        val sentenceKey: String?,
        val startDate: LocalDate,
        val endDate: LocalDate?,
        val lastPhotoAddedAt: Instant
    )
}
