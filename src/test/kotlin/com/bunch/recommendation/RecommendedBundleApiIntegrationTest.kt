package com.bunch.recommendation

import com.bunch.bundle.dto.BundleActor
import com.bunch.bundle.dto.CreateBundleRequest
import com.bunch.bundle.service.BundleService
import com.bunch.global.exception.ErrorCode
import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The recommended bundle sheet and the draft made from a recommended bundle, with the new combination switched on
 * (#184): the list and the photos of a card are read from the recommended bundles of the new combination, a draft takes
 * one and deletes it, and the photos it names are noted as used. The bundles are put in the tables as the calculation
 * saves them, because what is tested here is what is shown, not what is calculated.
 */
@SpringBootTest(properties = ["reco.combination.enabled=true"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecommendedBundleApiIntegrationTest : RecommendationIntegrationSupport() {
    @Autowired
    private lateinit var bundles: BundleService

    private val imageUrl = "https://res.cloudinary.com/test/image/upload/test-http/photo.jpg"
    private val day = "2026-10-19"

    // The other member of the bunch made by [bunchOfTwo]: a draft names the members that take part besides its maker.
    private var partner = 0L

    @AfterEach
    fun cleanNewFixtures() {
        jdbc.update("DELETE FROM recommendation_used_photos")
        jdbc.update("DELETE FROM recommended_bundle_recalc_queue")
        jdbc.update("DELETE FROM recommended_bundle_photos")
        jdbc.update("DELETE FROM recommended_bundles")
    }

    // --- the list ---

    @Test
    fun `a bunch without recommended bundles gets an empty list`() {
        val (ownerId, bunchId) = bunchWithOwner()

        assertThat(list(ownerId, bunchId).path("bundles")).isEmpty()
    }

    @Test
    fun `a card has the values the sheet shows and what the new combination adds`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val other = joined(ownerId, bunchId)
        val photoIds = shoot(bunchId, day, "12:00", 8, listOf(ownerId, other))
        val id = insertBundle(bunchId, "A lunch", photoIds, activity = "MEAL")

        val card = list(ownerId, bunchId).path("bundles").single()

        assertThat(card.path("id").asLong()).isEqualTo(id)
        assertThat(card.path("title").asText()).isEqualTo("A lunch")
        assertThat(card.path("startDate").asText()).isEqualTo(day)
        assertThat(card.path("endDate").isNull).isTrue()
        assertThat(card.path("photoCount").asInt()).isEqualTo(8)
        assertThat(card.path("coverImageUrl").asText()).isEqualTo(imageUrl)
        val members = card.path("uploaders").map { it.path("memberId").asLong() }
        assertThat(members).containsExactlyInAnyOrder(ownerId, other)
        assertThat(card.path("kind").asText()).isEqualTo("GENERAL")
        assertThat(card.path("activity").asText()).isEqualTo("MEAL")
        assertThat(card.has("eventType")).isFalse()
    }

    @Test
    fun `an event card says its kind and its event, and has no activity`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photoIds = shoot(bunchId, day, "19:00", 7, listOf(ownerId))
        insertBundle(bunchId, "A birthday", photoIds, kind = "EVENT", eventType = "BIRTHDAY", activity = null)

        val card = list(ownerId, bunchId).path("bundles").single()

        assertThat(card.path("kind").asText()).isEqualTo("EVENT")
        assertThat(card.path("eventType").asText()).isEqualTo("BIRTHDAY")
        assertThat(card.has("activity")).isFalse()
    }

    @Test
    fun `a card counts the photos of the card, and the first place is the cover`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photoIds = shoot(bunchId, day, "10:00", 12, listOf(ownerId))
        insertBundle(bunchId, "Many", photoIds, cardCount = 10)

        val card = list(ownerId, bunchId).path("bundles").single()
        val detail = detail(ownerId, bunchId, card.path("id").asLong())

        assertThat(card.path("photoCount").asInt()).isEqualTo(10)
        val shown = detail.path("photos").map { it.path("photoId").asLong() }
        assertThat(shown).containsExactlyElementsOf(photoIds.take(10))
    }

    @Test
    fun `the end day is given only when the photos reach over midnight`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val late = insertPhoto(bunchId, "2026-10-19", "23:30", uploaders = listOf(ownerId))
        val early = insertPhoto(bunchId, "2026-10-20", "00:40", uploaders = listOf(ownerId))
        insertBundle(bunchId, "Over midnight", listOf(late, early))

        val card = list(ownerId, bunchId).path("bundles").single()

        assertThat(card.path("startDate").asText()).isEqualTo("2026-10-19")
        assertThat(card.path("endDate").asText()).isEqualTo("2026-10-20")
    }

    @Test
    fun `the answer keeps every field the app reads`() {
        val (ownerId, bunchId) = bunchWithOwner()
        insertBundle(bunchId, "Old fields", shoot(bunchId, day, "12:00", 6, listOf(ownerId)))

        val card = list(ownerId, bunchId).path("bundles").single()

        assertThat(card.fieldNames().asSequence().toList())
            .contains("id", "title", "startDate", "endDate", "photoCount", "uploaders", "coverImageUrl")
    }

    @Test
    fun `the members of a card start with the one who uploaded the most photos`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val other = joined(ownerId, bunchId)
        val photoIds = shoot(bunchId, day, "12:00", 6, listOf(other, other, other, ownerId, ownerId, other))
        insertBundle(bunchId, "Chips", photoIds)

        val card = list(ownerId, bunchId).path("bundles").single()

        assertThat(card.path("uploaders").map { it.path("memberId").asLong() }).containsExactly(other, ownerId)
    }

    @Test
    fun `a bundle that is not shown yet is not in the list and is not found`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photoIds = shoot(bunchId, day, "12:00", 6, listOf(ownerId))
        val later = insertBundle(bunchId, "Later", photoIds, visibleFrom = Instant.now().plusSeconds(3_600))

        assertThat(list(ownerId, bunchId).path("bundles")).isEmpty()
        request(ownerId, bunchId, later).expectError(ErrorCode.RESOURCE_NOT_FOUND)
    }

    @Test
    fun `a bundle whose time to be shown from came is shown`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photoIds = shoot(bunchId, day, "12:00", 6, listOf(ownerId))
        insertBundle(bunchId, "Now", photoIds, visibleFrom = Instant.now().minusSeconds(1))

        assertThat(list(ownerId, bunchId).path("bundles")).hasSize(1)
    }

    // --- the order and the size of the list ---

    @Test
    fun `the list is in the order of the last photo added, then the members, then the photos`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val other = joined(ownerId, bunchId)
        val now = Instant.now()
        val old = insertBundle(bunchId, "Old", shoot(bunchId, day, "09:00", 6, listOf(ownerId)), activity = "CAFE",
            lastAdded = now.minusSeconds(5 * 86_400))
        val fewMembers = insertBundle(bunchId, "One member", shoot(bunchId, day, "12:00", 9, listOf(ownerId)),
            activity = "MEAL", lastAdded = now.minusSeconds(86_400))
        val manyMembers = insertBundle(bunchId, "Two members", shoot(bunchId, day, "15:00", 6, listOf(ownerId, other)),
            activity = "WALK", lastAdded = now.minusSeconds(86_400))
        val recent = insertBundle(bunchId, "Recent", shoot(bunchId, day, "18:00", 6, listOf(ownerId)),
            activity = "DRINKS", lastAdded = now.minusSeconds(3_600))

        val ids = list(ownerId, bunchId).path("bundles").map { it.path("id").asLong() }

        assertThat(ids).containsExactly(recent, manyMembers, fewMembers, old)
    }

    @Test
    fun `only three general bundles of an activity are listed`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val now = Instant.now()
        val ids = (1..4).map {
            val date = LocalDate.parse(day).plusDays(it.toLong()).toString()
            insertBundle(bunchId, "Lunch $it", shoot(bunchId, date, "12:00", 6, listOf(ownerId)), activity = "MEAL",
                dayKey = date, lastAdded = now.minusSeconds(it * 3_600L))
        }

        val listed = list(ownerId, bunchId).path("bundles").map { it.path("id").asLong() }

        assertThat(listed).hasSize(3)
        assertThat(listed).doesNotContain(ids[0])
    }

    // --- what is left of a card ---

    @Test
    fun `a bundle with no photo of its card left is left out and is not found`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photoIds = shoot(bunchId, day, "12:00", 6, listOf(ownerId))
        val id = insertBundle(bunchId, "Gone", photoIds)
        jdbc.update("DELETE FROM photos WHERE id IN (${photoIds.joinToString()})")

        assertThat(list(ownerId, bunchId).path("bundles")).isEmpty()
        request(ownerId, bunchId, id).expectError(ErrorCode.RESOURCE_NOT_FOUND)
    }

    @Test
    fun `a photo whose date was cleared is left out of the card`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photoIds = shoot(bunchId, day, "12:00", 6, listOf(ownerId))
        val id = insertBundle(bunchId, "Cleared", photoIds)
        jdbc.update(
            "UPDATE photos SET captured_date = NULL, captured_time = NULL, date_source = 'UNKNOWN' WHERE id = ?",
            photoIds.first()
        )

        val photos = detail(ownerId, bunchId, id).path("photos").map { it.path("photoId").asLong() }

        assertThat(photos).containsExactlyElementsOf(photoIds.drop(1))
        assertThat(list(ownerId, bunchId).path("bundles").single().path("photoCount").asInt()).isEqualTo(5)
    }

    // --- the photos of a card ---

    @Test
    fun `the photos of a card come in the order of their places, with the fields of the viewer`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photoIds = shoot(bunchId, day, "12:00", 8, listOf(ownerId))
        val id = insertBundle(bunchId, "Detail", photoIds, kind = "EVENT", eventType = "BIRTHDAY", activity = null)

        val detail = detail(ownerId, bunchId, id)

        assertThat(detail.path("id").asLong()).isEqualTo(id)
        assertThat(detail.path("title").asText()).isEqualTo("Detail")
        assertThat(detail.path("kind").asText()).isEqualTo("EVENT")
        assertThat(detail.path("eventType").asText()).isEqualTo("BIRTHDAY")
        val photos = detail.path("photos")
        assertThat(photos.map { it.path("photoId").asLong() }).containsExactlyElementsOf(photoIds)
        assertThat(photos.first().path("imageUrl").asText()).isEqualTo(imageUrl)
        assertThat(photos.first().path("capturedDate").asText()).isEqualTo(day)
        assertThat(photos.first().path("uploaders").first().path("memberId").asLong()).isEqualTo(ownerId)
    }

    // --- who may read ---

    @Test
    fun `a bundle of another bunch is not found, and a stranger may not read the sheet`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val (otherOwner, otherBunch) = bunchWithOwner()
        val foreign = insertBundle(otherBunch, "Other", shoot(otherBunch, day, "12:00", 6, listOf(otherOwner)))

        request(ownerId, bunchId, foreign).expectError(ErrorCode.RESOURCE_NOT_FOUND)
        request(ownerId, bunchId, Long.MAX_VALUE).expectError(ErrorCode.RESOURCE_NOT_FOUND)
        request(otherOwner, bunchId).expectError(ErrorCode.FORBIDDEN)
        request(otherOwner, bunchId, foreign).expectError(ErrorCode.FORBIDDEN)
    }

    // --- making a draft from a recommended bundle ---

    @Test
    fun `a draft takes the bundle, which is deleted with its photo rows, and gets its title and its first day`() {
        val (ownerId, bunchId) = bunchOfTwo()
        val photoIds = shoot(bunchId, day, "12:00", 12, listOf(ownerId))
        val id = insertBundle(bunchId, "A lunch", photoIds, cardCount = 10)

        val draft = draftOf(ownerId, bunchId, photoIds.take(10), id)

        assertThat(draft.title).isEqualTo("A lunch")
        assertThat(recommendedDateOf(draft.id)).isEqualTo(LocalDate.parse(day))
        assertThat(count("recommended_bundles", "id = $id")).isZero()
        assertThat(count("recommended_bundle_photos", "bundle_id = $id")).isZero()
        assertThat(list(ownerId, bunchId).path("bundles")).isEmpty()
    }

    @Test
    fun `all the photos of the bundle are noted as used, also the ones the card or the draft did not take`() {
        val (ownerId, bunchId) = bunchOfTwo()
        val photoIds = shoot(bunchId, day, "12:00", 12, listOf(ownerId))
        val id = insertBundle(bunchId, "A lunch", photoIds, cardCount = 10)

        draftOf(ownerId, bunchId, photoIds.take(7), id)

        assertThat(usedPhotoIds(bunchId)).containsExactlyInAnyOrderElementsOf(photoIds)
    }

    @Test
    fun `a draft made of a bundle that is gone is made from its photos, without a title`() {
        val (ownerId, bunchId) = bunchOfTwo()
        val photoIds = shoot(bunchId, day, "12:00", 6, listOf(ownerId))

        val draft = draftOf(ownerId, bunchId, photoIds, 999_999_999L)

        assertThat(draft.title).isEmpty()
        assertThat(recommendedDateOf(draft.id)).isNull()
        assertThat(usedPhotoIds(bunchId)).isEmpty()
    }

    @Test
    fun `a bundle of another bunch is ignored by a draft and stays`() {
        val (ownerId, bunchId) = bunchOfTwo()
        val (otherOwner, otherBunch) = bunchWithOwner()
        val foreign = insertBundle(otherBunch, "Other", shoot(otherBunch, day, "12:00", 6, listOf(otherOwner)))
        val photoIds = shoot(bunchId, day, "12:00", 6, listOf(ownerId))

        val draft = draftOf(ownerId, bunchId, photoIds, foreign)

        assertThat(draft.title).isEmpty()
        assertThat(count("recommended_bundles", "id = $foreign")).isEqualTo(1)
        assertThat(usedPhotoIds(otherBunch)).isEmpty()
    }

    @Test
    fun `a bundle that is not shown yet is not taken by a draft`() {
        val (ownerId, bunchId) = bunchOfTwo()
        val photoIds = shoot(bunchId, day, "12:00", 6, listOf(ownerId))
        val later = insertBundle(bunchId, "Later", photoIds, visibleFrom = Instant.now().plusSeconds(3_600))

        val draft = draftOf(ownerId, bunchId, photoIds, later)

        assertThat(draft.title).isEmpty()
        assertThat(count("recommended_bundles", "id = $later")).isEqualTo(1)
    }

    @Test
    fun `a draft made without a recommended bundle leaves the bundles and notes no used photos`() {
        val (ownerId, bunchId) = bunchOfTwo()
        val photoIds = shoot(bunchId, day, "12:00", 12, listOf(ownerId))
        val id = insertBundle(bunchId, "A lunch", photoIds.take(6))

        draftOf(ownerId, bunchId, photoIds.drop(6), null)

        assertThat(count("recommended_bundles", "id = $id")).isEqualTo(1)
        assertThat(usedPhotoIds(bunchId)).isEmpty()
    }

    // --- helpers ---

    // A draft needs a bunch of two members at least.
    private fun bunchOfTwo(): Pair<Long, Long> {
        val (ownerId, bunchId) = bunchWithOwner()
        partner = joined(ownerId, bunchId)
        return ownerId to bunchId
    }

    private fun draftOf(memberId: Long, bunchId: Long, photoIds: List<Long>, recommendationId: Long?) =
        bundles.create(
            BundleActor(memberId, "session"),
            bunchId,
            UUID.randomUUID(),
            CreateBundleRequest(photoIds, recommendationId, participantMemberIds = listOf(partner))
        )

    // A recommended bundle as the calculation saves it: the first [cardCount] photos are on the card, in this order.
    @Suppress("LongParameterList")
    private fun insertBundle(
        bunchId: Long,
        title: String,
        photoIds: List<Long>,
        kind: String = "GENERAL",
        eventType: String? = null,
        activity: String? = "MEAL",
        cardCount: Int = photoIds.size.coerceAtMost(10),
        visibleFrom: Instant = Instant.now().minusSeconds(3_600),
        lastAdded: Instant = Instant.now().minusSeconds(3_600),
        dayKey: String = day
    ): Long {
        val id = jdbc.queryForObject(
            """INSERT INTO recommended_bundles (bunch_id, day_key, stable_key, kind, event_type, established_by,
                activity, title, start_at, end_at, photo_count, uploader_count, has_group_photo, rule_trace,
                visible_from, last_photo_added_at, created_at, updated_at)
                VALUES (?, CAST(? AS date), ?, ?, ?, ?, ?, ?, now(), now(), ?, 1, false, '{}',
                ?, ?, now(), now()) RETURNING id""",
            Long::class.java,
            bunchId, dayKey, UUID.randomUUID().toString().replace("-", ""), kind, eventType,
            if (kind == "EVENT") "STRONG" else null, activity, title, photoIds.size,
            Timestamp.from(visibleFrom), Timestamp.from(lastAdded)
        )!!
        photoIds.forEachIndexed { index, photoId ->
            jdbc.update(
                "INSERT INTO recommended_bundle_photos (bundle_id, photo_id, role, card_position) VALUES (?, ?, ?, ?)",
                id, photoId, if (kind == "EVENT") "CLUE" else "GENERAL", if (index < cardCount) index + 1 else null
            )
        }
        return id
    }

    private fun list(memberId: Long, bunchId: Long): JsonNode =
        request(memberId, bunchId).andExpect(status().isOk).andReturn().data()

    private fun detail(memberId: Long, bunchId: Long, id: Long): JsonNode =
        request(memberId, bunchId, id).andExpect(status().isOk).andReturn().data()

    private fun request(memberId: Long, bunchId: Long, id: Long? = null): ResultActions {
        val path = "/api/v1/bunches/$bunchId/recommended-bundles" + (id?.let { "/$it" } ?: "")

        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(memberId)))
    }

    private fun count(table: String, where: String): Int =
        jdbc.queryForObject("SELECT count(*) FROM $table WHERE $where", Int::class.java)!!

    private fun usedPhotoIds(bunchId: Long): List<Long> = jdbc.queryForList(
        "SELECT photo_id FROM recommendation_used_photos WHERE bunch_id = ?",
        Long::class.java,
        bunchId
    )

    private fun recommendedDateOf(bundleId: Long): LocalDate? = jdbc.queryForList(
        "SELECT recommended_date FROM bundles WHERE id = ?",
        java.sql.Date::class.java,
        bundleId
    ).single()?.toLocalDate()
}
