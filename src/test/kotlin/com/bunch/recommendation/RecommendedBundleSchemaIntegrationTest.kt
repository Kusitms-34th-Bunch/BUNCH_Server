package com.bunch.recommendation

import com.bunch.photo.PhotoHttpTestSupport
import com.bunch.recommendation.domain.RecommendedBundle
import com.bunch.recommendation.domain.RecommendedBundlePhoto
import com.bunch.recommendation.domain.RecommendedBundlePhotoRepository
import com.bunch.recommendation.domain.RecommendedBundleQueueRepository
import com.bunch.recommendation.domain.RecommendedBundleRepository
import com.bunch.recommendation.domain.combination.ActivityCode
import com.bunch.recommendation.domain.combination.BundleKind
import com.bunch.recommendation.domain.combination.EstablishedBy
import com.bunch.recommendation.domain.combination.EventType
import com.bunch.recommendation.domain.combination.PhotoRole
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The tables of the recommended bundles of the new combination and the statements that read and write them
 * (recommended bundle specification 4-5, 10-1 to 10-3), on a real PostgreSQL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecommendedBundleSchemaIntegrationTest : PhotoHttpTestSupport() {
    @Autowired
    private lateinit var bundles: RecommendedBundleRepository

    @Autowired
    private lateinit var bundlePhotos: RecommendedBundlePhotoRepository

    @Autowired
    private lateinit var queue: RecommendedBundleQueueRepository

    private val day = LocalDate.parse("2026-10-19")

    @AfterEach
    fun cleanFixtures() {
        jdbc.update("DELETE FROM recommended_bundle_recalc_queue")
        jdbc.update("DELETE FROM recommended_bundle_photos")
        jdbc.update("DELETE FROM recommended_bundles")
        jdbc.update("DELETE FROM photo_uploaders")
        jdbc.update("DELETE FROM photos")
        jdbc.update("DELETE FROM image_assets WHERE object_key LIKE 'photos/test-http-%/original.jpg'")
    }

    // --- the table of the bundles ---

    @Test
    fun `a general bundle and an event bundle are stored`() {
        val (_, bunchId) = bunchWithOwner()

        val general = bundle(bunchId)
        val event = bundle(bunchId, kind = "EVENT", eventType = "BIRTHDAY", establishedBy = "STRONG")

        assertThat(count("recommended_bundles", "id IN ($general, $event)")).isEqualTo(2)
    }

    @Test
    fun `an event bundle has an event type and what made it stand, a general bundle has neither`() {
        val (_, bunchId) = bunchWithOwner()

        assertRejected { bundle(bunchId, kind = "EVENT") }
        assertRejected { bundle(bunchId, kind = "EVENT", eventType = "BIRTHDAY") }
        assertRejected { bundle(bunchId, kind = "EVENT", establishedBy = "STRONG") }
        assertRejected { bundle(bunchId, kind = "GENERAL", eventType = "BIRTHDAY", establishedBy = "STRONG") }
        assertRejected { bundle(bunchId, kind = "GENERAL", establishedBy = "STRONG") }
    }

    @Test
    fun `only the codes of the specification are stored`() {
        val (_, bunchId) = bunchWithOwner()

        assertRejected { bundle(bunchId, kind = "OTHER") }
        assertRejected { bundle(bunchId, kind = "EVENT", eventType = "HALLOWEEN", establishedBy = "STRONG") }
        assertRejected { bundle(bunchId, kind = "EVENT", eventType = "BIRTHDAY", establishedBy = "WEAK") }
        assertRejected { bundle(bunchId, activity = "SKYDIVING") }
    }

    @Test
    fun `every code of the code lists can be stored`() {
        val (_, bunchId) = bunchWithOwner()

        EventType.entries.forEach { bundle(bunchId, kind = "EVENT", eventType = it.name, establishedBy = "STRONG") }
        ActivityCode.entries.forEach { bundle(bunchId, activity = it.name) }
        EstablishedBy.entries.forEach { bundle(bunchId, kind = "EVENT", eventType = "TRAVEL", establishedBy = it.name) }

        assertThat(count("recommended_bundles", "bunch_id = $bunchId")).isEqualTo(
            EventType.entries.size + ActivityCode.entries.size + EstablishedBy.entries.size
        )
    }

    @Test
    fun `a stable key is used once in a bunch, and may be used in another`() {
        val (_, bunchId) = bunchWithOwner()
        val (_, otherBunchId) = bunchWithOwner()
        bundle(bunchId, stableKey = "same")

        assertRejected { bundle(bunchId, stableKey = "same") }
        bundle(otherBunchId, stableKey = "same")
    }

    @Test
    fun `a title is not blank and has no line break`() {
        val (_, bunchId) = bunchWithOwner()

        assertRejected { bundle(bunchId, title = "") }
        assertRejected { bundle(bunchId, title = "   ") }
        assertRejected { bundle(bunchId, title = "10월 19일\n밤") }
        assertRejected { bundle(bunchId, title = "10월 19일\r밤") }
    }

    @Test
    fun `a bundle does not end before it starts, a part starts from 1 and the counts are not negative`() {
        val (_, bunchId) = bunchWithOwner()

        assertRejected { bundle(bunchId, start = "2026-10-19T12:00:00Z", end = "2026-10-19T11:00:00Z") }
        assertRejected { bundle(bunchId, sub = 0) }
        assertRejected { bundle(bunchId, photoCount = -1) }
        assertRejected { bundle(bunchId, uploaderCount = -1) }
        bundle(bunchId, start = "2026-10-19T12:00:00Z", end = "2026-10-19T12:00:00Z")
        bundle(bunchId, sub = 1)
    }

    @Test
    fun `the way a bundle was made is stored as JSON and can be read by what it holds`() {
        val (_, bunchId) = bunchWithOwner()
        val id = bundle(bunchId, trace = """{"kind":"GENERAL","counts":{"photos":8,"uploaders":2}}""")

        val photos = jdbc.queryForObject(
            "SELECT (rule_trace -> 'counts' ->> 'photos')::int FROM recommended_bundles WHERE id = ?",
            Int::class.java,
            id
        )

        assertThat(photos).isEqualTo(8)
    }

    // --- the table of the photos ---

    @Test
    fun `a photo is in one recommended bundle only`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photoId = photo(bunchId, ownerId, "20:00:00")
        val first = bundle(bunchId)
        val second = bundle(bunchId)
        bundlePhoto(first, photoId)

        assertRejected { bundlePhoto(second, photoId) }
        assertRejected { bundlePhoto(first, photoId) }
    }

    @Test
    fun `a place on the card is from 1 and is used once in a bundle, the photos off the card have none`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val one = photo(bunchId, ownerId, "20:00:00")
        val two = photo(bunchId, ownerId, "20:10:00")
        val three = photo(bunchId, ownerId, "20:20:00")
        val four = photo(bunchId, ownerId, "20:30:00")
        val other = bundle(bunchId)
        val id = bundle(bunchId)
        bundlePhoto(id, one, position = 1)
        bundlePhoto(id, two, position = 2)
        bundlePhoto(id, three)
        bundlePhoto(id, four)

        assertRejected { bundlePhoto(id, photo(bunchId, ownerId, "20:40:00"), position = 2) }
        assertRejected { bundlePhoto(id, photo(bunchId, ownerId, "20:50:00"), position = 0) }
        bundlePhoto(other, photo(bunchId, ownerId, "21:00:00"), position = 1)
    }

    @Test
    fun `only the roles of the specification are stored`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val id = bundle(bunchId)

        PhotoRole.entries.forEach { bundlePhoto(id, photo(bunchId, ownerId, "20:00:00"), role = it.name) }

        assertRejected { bundlePhoto(id, photo(bunchId, ownerId, "20:10:00"), role = "COVER") }
    }

    // --- what the database deletes with what ---

    @Test
    fun `deleting a bunch deletes its recommended bundles, their photos and its days in the queue`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val id = bundle(bunchId)
        bundlePhoto(id, photo(bunchId, ownerId, "20:00:00"), position = 1)
        queue.put(bunchId, day, Instant.parse("2026-10-19T12:00:00Z"))

        jdbc.update("DELETE FROM bunches WHERE id = ?", bunchId)

        assertThat(count("recommended_bundles", "id = $id")).isZero()
        assertThat(count("recommended_bundle_photos", "bundle_id = $id")).isZero()
        assertThat(count("recommended_bundle_recalc_queue", "bunch_id = $bunchId")).isZero()
    }

    @Test
    fun `deleting a photo deletes only its row of a bundle`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val gone = photo(bunchId, ownerId, "20:00:00")
        val stays = photo(bunchId, ownerId, "20:10:00")
        val id = bundle(bunchId)
        bundlePhoto(id, gone, position = 1)
        bundlePhoto(id, stays, position = 2)

        jdbc.update("DELETE FROM photos WHERE id = ?", gone)

        assertThat(count("recommended_bundles", "id = $id")).isEqualTo(1)
        assertThat(count("recommended_bundle_photos", "bundle_id = $id")).isEqualTo(1)
        assertThat(count("recommended_bundle_photos", "photo_id = $stays")).isEqualTo(1)
    }

    @Test
    fun `deleting a bundle deletes its photos but not the photos themselves`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photoId = photo(bunchId, ownerId, "20:00:00")
        val id = bundle(bunchId)
        bundlePhoto(id, photoId)

        jdbc.update("DELETE FROM recommended_bundles WHERE id = ?", id)

        assertThat(count("recommended_bundle_photos", "bundle_id = $id")).isZero()
        assertThat(count("photos", "id = $photoId")).isEqualTo(1)
    }

    // --- the entities ---

    @Test
    fun `a bundle is saved and read back with what the entity holds, the JSON trace too`() {
        val (_, bunchId) = bunchWithOwner()
        val saved = bundles.saveAndFlush(
            RecommendedBundle(
                bunchId = bunchId,
                dayKey = day,
                stableKey = "key-${UUID.randomUUID()}",
                kind = BundleKind.EVENT,
                eventType = EventType.BIRTHDAY,
                establishedBy = EstablishedBy.STRONG,
                activity = null,
                subIndex = null,
                title = "10월 19일 생일 파티",
                startAt = Instant.parse("2026-10-19T10:00:00Z"),
                endAt = Instant.parse("2026-10-19T12:00:00Z"),
                photoCount = 8,
                uploaderCount = 3,
                hasGroupPhoto = true,
                ruleTrace = mapOf("kind" to "EVENT", "counts" to mapOf("photos" to 8, "uploaders" to 3)),
                visibleFrom = Instant.parse("2026-10-19T20:00:00Z"),
                lastPhotoAddedAt = Instant.parse("2026-10-19T10:30:00Z"),
                createdAt = Instant.parse("2026-10-19T11:00:00Z")
            )
        )

        val read = bundles.findById(saved.id).orElseThrow()

        assertThat(read.title).isEqualTo("10월 19일 생일 파티")
        assertThat(read.kind).isEqualTo(BundleKind.EVENT)
        assertThat(read.eventType).isEqualTo(EventType.BIRTHDAY)
        assertThat(read.establishedBy).isEqualTo(EstablishedBy.STRONG)
        assertThat(read.hasGroupPhoto).isTrue()
        assertThat(read.visibleFrom).isEqualTo(Instant.parse("2026-10-19T20:00:00Z"))
        assertThat(read.createdAt).isEqualTo(Instant.parse("2026-10-19T11:00:00Z"))
        assertThat(read.lastPhotoAddedAt).isEqualTo(Instant.parse("2026-10-19T10:30:00Z"))
        assertThat(read.ruleTrace["kind"]).isEqualTo("EVENT")
        @Suppress("UNCHECKED_CAST")
        assertThat((read.ruleTrace["counts"] as Map<String, Any?>)["photos"]).isEqualTo(8)
    }

    @Test
    fun `the bundles of some days of a bunch are found together and no others`() {
        val (_, bunchId) = bunchWithOwner()
        val (_, otherBunchId) = bunchWithOwner()
        val first = bundleEntity(bunchId, day)
        val second = bundleEntity(bunchId, day.plusDays(1))
        bundleEntity(bunchId, day.plusDays(2))
        bundleEntity(otherBunchId, day)

        val found = bundles.findByBunchIdAndDayKeyIn(bunchId, listOf(day, day.plusDays(1)))

        assertThat(found.map { it.id }).containsExactlyInAnyOrder(first.id, second.id)
    }

    @Test
    fun `the photos of some bundles are deleted together, the photos of others stay`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val first = bundleEntity(bunchId, day)
        val second = bundleEntity(bunchId, day)
        val kept = bundleEntity(bunchId, day.plusDays(1))
        listOf(first, second, kept).forEach {
            val photoId = photo(bunchId, ownerId, "20:00:00")
            bundlePhotos.saveAndFlush(
                RecommendedBundlePhoto(bundleId = it.id, photoId = photoId, role = PhotoRole.GENERAL)
            )
        }

        val deleted = bundlePhotos.deleteByBundleIds(listOf(first.id, second.id))

        assertThat(deleted).isEqualTo(2)
        assertThat(bundlePhotos.findByBundleIdIn(listOf(first.id, second.id, kept.id)).map { it.bundleId })
            .containsExactly(kept.id)
    }

    // --- the expiry (4.2.9 rule 4) ---

    @Test
    fun `the time the last photo was added is kept and is never empty`() {
        val (_, bunchId) = bunchWithOwner()
        val id = bundle(bunchId)

        val kept = jdbc.queryForObject(
            "SELECT last_photo_added_at IS NOT NULL FROM recommended_bundles WHERE id = ?",
            Boolean::class.java,
            id
        )

        assertThat(kept).isTrue()
        assertRejected { jdbc.update("UPDATE recommended_bundles SET last_photo_added_at = NULL WHERE id = ?", id) }
    }

    @Test
    fun `the bundles whose last photo was added before the time are deleted with their photos, the others stay`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val old = bundleAddedAt(bunchId, "2026-10-01T10:00:00Z")
        val recent = bundleAddedAt(bunchId, "2026-10-20T10:00:00Z")
        val oldPhoto = photo(bunchId, ownerId, "20:00:00")
        val recentPhoto = photo(bunchId, ownerId, "20:10:00")
        bundlePhoto(old, oldPhoto, position = 1)
        bundlePhoto(recent, recentPhoto, position = 1)

        val deleted = bundles.deleteExpired(Instant.parse("2026-10-02T00:00:00Z"))

        assertThat(deleted).isEqualTo(1)
        assertThat(count("recommended_bundles", "id = $old")).isZero()
        assertThat(count("recommended_bundle_photos", "bundle_id = $old")).isZero()
        assertThat(count("recommended_bundles", "id = $recent")).isEqualTo(1)
        assertThat(count("recommended_bundle_photos", "bundle_id = $recent")).isEqualTo(1)
        assertThat(count("photos", "id = $oldPhoto")).isEqualTo(1)
    }

    @Test
    fun `a bundle whose last photo was added exactly at the time is not deleted`() {
        val (_, bunchId) = bunchWithOwner()
        val id = bundleAddedAt(bunchId, "2026-10-02T00:00:00Z")

        assertThat(bundles.deleteExpired(Instant.parse("2026-10-02T00:00:00Z"))).isZero()
        assertThat(count("recommended_bundles", "id = $id")).isEqualTo(1)
    }

    // --- the queue of the days (10-1, 10-2) ---

    private val soon = Instant.parse("2026-10-19T12:05:00Z")

    @Test
    fun `a day is put in the queue once, and the time of the day is moved by another change`() {
        val (_, bunchId) = bunchWithOwner()

        queue.put(bunchId, day, soon)
        queue.put(bunchId, day, soon.plusSeconds(120))

        assertThat(count("recommended_bundle_recalc_queue", "bunch_id = $bunchId")).isEqualTo(1)
        assertThat(dueOf(bunchId, day)).isEqualTo(soon.plusSeconds(120))
    }

    @Test
    fun `only the days that are due are found, the bunches in the order of their numbers`() {
        val (_, bunchId) = bunchWithOwner()
        val (_, otherBunchId) = bunchWithOwner()
        queue.put(otherBunchId, day, soon)
        queue.put(bunchId, day, soon)
        queue.put(bunchId, day.plusDays(1), soon.plusSeconds(60))
        queue.put(bunchId, day.plusDays(2), soon.plusSeconds(600))

        assertThat(queue.findDueBunchIds(soon.minusSeconds(1), 10)).isEmpty()
        assertThat(queue.findDueBunchIds(soon, 10)).containsExactly(bunchId, otherBunchId)
        assertThat(queue.findDueDays(bunchId, soon.plusSeconds(60)).map { it.dayKey })
            .containsExactly(day, day.plusDays(1))
        assertThat(queue.findDueDays(bunchId, soon.plusSeconds(600)).map { it.dayKey })
            .containsExactly(day, day.plusDays(1), day.plusDays(2))
    }

    @Test
    fun `a bunch with several days due is found once, and the number of bunches is limited`() {
        val (_, bunchId) = bunchWithOwner()
        val (_, otherBunchId) = bunchWithOwner()
        queue.put(bunchId, day, soon)
        queue.put(bunchId, day.plusDays(1), soon)
        queue.put(otherBunchId, day, soon)

        assertThat(queue.findDueBunchIds(soon, 10)).containsExactly(bunchId, otherBunchId)
        assertThat(queue.findDueBunchIds(soon, 1)).containsExactly(bunchId)
    }

    @Test
    fun `the days that were calculated leave the queue, a day moved later meanwhile stays`() {
        val (_, bunchId) = bunchWithOwner()
        queue.put(bunchId, day, soon)
        queue.put(bunchId, day.plusDays(1), soon)
        val calculatedAt = soon.plusSeconds(10)
        // A change comes while the days are being calculated and moves the time of the second day later.
        queue.put(bunchId, day.plusDays(1), calculatedAt.plusSeconds(300))

        val removed = queue.deleteDue(bunchId, listOf(day, day.plusDays(1)), calculatedAt)

        assertThat(removed).isEqualTo(1)
        assertThat(queue.findDueDays(bunchId, calculatedAt.plusSeconds(600)).map { it.dayKey })
            .containsExactly(day.plusDays(1))
    }

    @Test
    fun `a day of another bunch or another day is not taken out of the queue`() {
        val (_, bunchId) = bunchWithOwner()
        val (_, otherBunchId) = bunchWithOwner()
        queue.put(bunchId, day, soon)
        queue.put(bunchId, day.plusDays(1), soon)
        queue.put(otherBunchId, day, soon)

        queue.deleteDue(bunchId, listOf(day), soon)

        assertThat(count("recommended_bundle_recalc_queue", "bunch_id = $bunchId")).isEqualTo(1)
        assertThat(count("recommended_bundle_recalc_queue", "bunch_id = $otherBunchId")).isEqualTo(1)
    }

    // --- helpers ---

    private fun bundleEntity(bunchId: Long, dayKey: LocalDate): RecommendedBundle = bundles.saveAndFlush(
        RecommendedBundle(
            bunchId = bunchId,
            dayKey = dayKey,
            stableKey = "key-${UUID.randomUUID()}",
            kind = BundleKind.GENERAL,
            eventType = null,
            establishedBy = null,
            activity = ActivityCode.MEAL,
            subIndex = null,
            title = "밤 식사",
            startAt = Instant.parse("2026-10-19T10:00:00Z"),
            endAt = Instant.parse("2026-10-19T11:00:00Z"),
            photoCount = 6,
            uploaderCount = 2,
            hasGroupPhoto = false,
            ruleTrace = emptyMap(),
            visibleFrom = Instant.parse("2026-10-19T20:00:00Z"),
            lastPhotoAddedAt = Instant.parse("2026-10-19T10:30:00Z"),
            createdAt = Instant.parse("2026-10-19T11:00:00Z")
        )
    )

    private fun bundle(
        bunchId: Long,
        stableKey: String = "key-${UUID.randomUUID()}",
        kind: String = "GENERAL",
        eventType: String? = null,
        establishedBy: String? = null,
        activity: String? = null,
        sub: Int? = null,
        title: String = "10월 19일 밤 식사",
        start: String = "2026-10-19T10:00:00Z",
        end: String = "2026-10-19T11:00:00Z",
        photoCount: Int = 6,
        uploaderCount: Int = 2,
        trace: String = "{}"
    ): Long = jdbc.queryForObject(
        """INSERT INTO recommended_bundles (bunch_id, day_key, stable_key, kind, event_type, established_by, activity,
            sub_index, title, start_at, end_at, photo_count, uploader_count, has_group_photo, rule_trace, visible_from,
            last_photo_added_at, created_at, updated_at)
            VALUES (?, DATE '2026-10-19', ?, ?, ?, ?, ?, ?, ?, CAST(? AS timestamptz), CAST(? AS timestamptz), ?, ?,
            false, CAST(? AS jsonb), now(), now(), now(), now()) RETURNING id""",
        Long::class.java,
        bunchId,
        stableKey,
        kind,
        eventType,
        establishedBy,
        activity,
        sub,
        title,
        start,
        end,
        photoCount,
        uploaderCount,
        trace
    )!!

    private fun bundleAddedAt(bunchId: Long, addedAt: String): Long {
        val id = bundle(bunchId)
        jdbc.update(
            "UPDATE recommended_bundles SET last_photo_added_at = CAST(? AS timestamptz) WHERE id = ?",
            addedAt,
            id
        )
        return id
    }

    private fun bundlePhoto(bundleId: Long, photoId: Long, role: String = "GENERAL", position: Int? = null) {
        jdbc.update(
            "INSERT INTO recommended_bundle_photos (bundle_id, photo_id, role, card_position) VALUES (?, ?, ?, ?)",
            bundleId,
            photoId,
            role,
            position
        )
    }

    private fun photo(bunchId: Long, ownerId: Long, time: String): Long =
        insertPhoto(bunchId, "2026-10-19", time, uploaders = listOf(ownerId))

    private fun dueOf(bunchId: Long, dayKey: LocalDate): Instant = jdbc.queryForObject(
        "SELECT due_at FROM recommended_bundle_recalc_queue WHERE bunch_id = ? AND day_key = ?",
        java.sql.Timestamp::class.java,
        bunchId,
        java.sql.Date.valueOf(dayKey)
    )!!.toInstant()

    private fun count(table: String, where: String): Int =
        jdbc.queryForObject("SELECT count(*) FROM $table WHERE $where", Int::class.java)!!

    private fun assertRejected(action: () -> Unit) {
        assertThatThrownBy(action).isInstanceOf(DataIntegrityViolationException::class.java)
    }
}
