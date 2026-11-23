package com.bunch.recommendation

import com.bunch.global.exception.BusinessException
import com.bunch.photo.PhotoHttpTestSupport
import com.bunch.photo.service.PhotoChangedEvent
import com.bunch.recommendation.combination.CombinerTestSupport.CLOCK
import com.bunch.recommendation.combination.CombinerTestSupport.shot
import com.bunch.recommendation.domain.RecommendedBundleRepository
import com.bunch.recommendation.domain.combination.ActivityCode
import com.bunch.recommendation.domain.combination.BundleCombiner
import com.bunch.recommendation.domain.combination.RecoPhoto
import com.bunch.recommendation.service.RecommendationScheduler
import com.bunch.recommendation.service.RecommendationService
import com.bunch.recommendation.service.RecommendedBundleReader
import com.bunch.recommendation.service.RecommendedBundleStore
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * The new recommended bundles shown and taken (photo box 4.2.1 to 4.2.5, 4.1.3, 4.1.4), with the switch on, on a real
 * PostgreSQL: the list of the cards, the photos of a card with the cover first, the taking of a bundle by a draft, and
 * the old recommended bundles that stop being calculated.
 */
@SpringBootTest(properties = ["reco.combination.enabled=true"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecommendedBundleReaderIntegrationTest : PhotoHttpTestSupport() {
    @Autowired
    private lateinit var store: RecommendedBundleStore

    @Autowired
    private lateinit var reader: RecommendedBundleReader

    @Autowired
    private lateinit var service: RecommendationService

    @Autowired
    private lateinit var scheduler: RecommendationScheduler

    @Autowired
    private lateinit var bundles: RecommendedBundleRepository

    @Autowired
    private lateinit var transactions: PlatformTransactionManager

    private val calculatedAt = Instant.parse("2026-10-25T03:00:00Z")

    @AfterEach
    fun cleanFixtures() {
        jdbc.update("DELETE FROM recommendation_used_photos")
        jdbc.update("DELETE FROM recommendation_dirty_dates")
        jdbc.update("DELETE FROM recommended_bundle_recalc_queue")
        jdbc.update("DELETE FROM recommended_bundle_photos")
        jdbc.update("DELETE FROM recommended_bundles")
        jdbc.update("DELETE FROM photo_uploaders")
        jdbc.update("DELETE FROM photos")
        jdbc.update("DELETE FROM image_assets WHERE public_id LIKE 'test-http/%'")
    }

    // --- the list (4.2.1, 4.2.2, 4.2.9 rule 6) ---

    @Test
    fun `the list has the card of a bundle with its title, dates, time range, members and cover`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val (_, other) = saveLunch(bunchId, ownerId)

        val card = reader.cards(ownerId, bunchId).bundles.single()

        val saved = bundles.findByBunchId(bunchId).single()
        assertThat(card.id).isEqualTo(saved.id)
        assertThat(card.title).isEqualTo("10월 19일 오전")
        assertThat(card.startDate).isEqualTo(LocalDate.parse("2026-10-19"))
        assertThat(card.endDate).isNull()
        assertThat(card.photoCount).isEqualTo(6)
        assertThat(card.startAt!!.toInstant()).isEqualTo(saved.startAt)
        assertThat(card.endAt!!.toInstant()).isEqualTo(saved.endAt)
        assertThat(card.startAt!!.offset.totalSeconds).isEqualTo(9 * 3600)
        assertThat(card.uploaders.map { it.memberId }).containsExactlyInAnyOrder(ownerId, other)
        assertThat(card.coverImageUrl).isNotBlank()
    }

    @Test
    fun `a bundle that goes past midnight has an end date`() {
        val (ownerId, bunchId) = bunchWithOwner()
        saveLunch(bunchId, ownerId)
        jdbc.update(
            "UPDATE recommended_bundles SET end_at = ? WHERE bunch_id = ?",
            Timestamp.from(Instant.parse("2026-10-19T16:30:00Z")),
            bunchId
        )

        val card = reader.cards(ownerId, bunchId).bundles.single()

        assertThat(card.startDate).isEqualTo(LocalDate.parse("2026-10-19"))
        assertThat(card.endDate).isEqualTo(LocalDate.parse("2026-10-20"))
    }

    @Test
    fun `the cover of the card is the cover of the bundle and not the first photo of the card`() {
        val (ownerId, bunchId) = bunchWithOwner()
        saveLunch(bunchId, ownerId)
        val onCard = cardPhotoIds(bundleId(bunchId))
        setCover(bunchId, onCard[3])

        val card = reader.cards(ownerId, bunchId).bundles.single()
        val detail = reader.photos(ownerId, bunchId, card.id)

        assertThat(detail.photos.first().photoId).isEqualTo(onCard[3])
        assertThat(card.coverImageUrl).isEqualTo(detail.photos.first().imageUrl)
    }

    @Test
    fun `the bundle whose last photo was added most recently is first, then the one with more photos on its card`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val other = joined(ownerId, bunchId)
        val photos = lunchPhotos(bunchId, ownerId, other, "2026-10-19", "10:00", 8) +
            lunchPhotos(bunchId, ownerId, other, "2026-10-20", "10:00", 20)
        save(bunchId, photos)
        val older = bundleId(bunchId, "2026-10-19")
        val newer = bundleId(bunchId, "2026-10-20")
        val sameTime = Timestamp.from(Instant.parse("2026-10-25T00:00:00Z"))
        jdbc.update("UPDATE recommended_bundles SET last_photo_added_at = ? WHERE bunch_id = ?", sameTime, bunchId)

        assertThat(reader.cards(ownerId, bunchId).bundles.map { it.id }).containsExactly(newer, older)

        jdbc.update(
            "UPDATE recommended_bundles SET last_photo_added_at = ? WHERE id = ?",
            Timestamp.from(Instant.parse("2026-10-26T00:00:00Z")),
            older
        )

        assertThat(reader.cards(ownerId, bunchId).bundles.map { it.id }).containsExactly(older, newer)
    }

    @Test
    fun `a bundle that is not shown yet is not in the list`() {
        val (ownerId, bunchId) = bunchWithOwner()
        saveLunch(bunchId, ownerId)
        jdbc.update(
            "UPDATE recommended_bundles SET visible_from = ? WHERE bunch_id = ?",
            Timestamp.from(Instant.now().plusSeconds(3600)),
            bunchId
        )

        assertThat(reader.cards(ownerId, bunchId).bundles).isEmpty()
    }

    @Test
    fun `a member of another bunch cannot see the list, and a bunch with no bundle gets an empty list`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val (strangerId, emptyBunch) = bunchWithOwner()
        saveLunch(bunchId, ownerId)

        assertThatThrownBy { reader.cards(strangerId, bunchId) }.isInstanceOf(BusinessException::class.java)
        assertThat(reader.cards(strangerId, emptyBunch).bundles).isEmpty()
    }

    @Test
    fun `the service gives what the reader gives while the switch is on`() {
        val (ownerId, bunchId) = bunchWithOwner()
        saveLunch(bunchId, ownerId)
        val id = bundleId(bunchId)

        assertThat(service.bundleCards(ownerId, bunchId)).isEqualTo(reader.cards(ownerId, bunchId))
        assertThat(service.bundlePhotos(ownerId, bunchId, id)).isEqualTo(reader.photos(ownerId, bunchId, id))
    }

    @Test
    fun `the API gives the list and the photos from the new recommended bundles while the switch is on`() {
        val (ownerId, bunchId) = bunchWithOwner()
        saveLunch(bunchId, ownerId)
        val id = bundleId(bunchId)
        val cover = cardPhotoIds(id)[3]
        setCover(bunchId, cover)
        val path = "/api/v1/bunches/$bunchId/recommended-bundles"
        val auth = bearer(ownerId)

        mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, auth))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.bundles[0].id").value(id))
            .andExpect(jsonPath("$.data.bundles[0].title").value("10월 19일 오전"))
            .andExpect(jsonPath("$.data.bundles[0].startAt").exists())
            .andExpect(jsonPath("$.data.bundles[0].endAt").exists())
        mvc.perform(get("$path/$id").header(HttpHeaders.AUTHORIZATION, auth))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.photos[0].photoId").value(cover))
            .andExpect(jsonPath("$.data.photos.length()").value(6))
        mvc.perform(get("$path/987654321").header(HttpHeaders.AUTHORIZATION, auth))
            .andExpect(status().isNotFound)
    }

    // --- the photos of a card (4.2.3, 4.2.5) ---

    @Test
    fun `the photos are the ones of the card, the cover first and the others in the order of the time`() {
        val (ownerId, bunchId) = bunchWithOwner()
        saveLunch(bunchId, ownerId, count = 12)
        val onCard = cardPhotoIds(bundleId(bunchId))
        setCover(bunchId, onCard[2])

        val detail = reader.photos(ownerId, bunchId, bundleId(bunchId))

        assertThat(detail.title).isEqualTo("10월 19일 오전")
        assertThat(detail.photos.map { it.photoId })
            .containsExactlyElementsOf(listOf(onCard[2]) + onCard.filter { it != onCard[2] })
        assertThat(detail.photos).hasSize(6)
        assertThat(detail.photos.first().capturedDate).isEqualTo(LocalDate.parse("2026-10-19"))
    }

    @Test
    fun `a bundle with no cover gives its photos in the order of the time`() {
        val (ownerId, bunchId) = bunchWithOwner()
        saveLunch(bunchId, ownerId)
        jdbc.update("UPDATE recommended_bundles SET cover_photo_id = NULL WHERE bunch_id = ?", bunchId)

        val detail = reader.photos(ownerId, bunchId, bundleId(bunchId))

        assertThat(detail.photos.map { it.photoId }).containsExactlyElementsOf(cardPhotoIds(bundleId(bunchId)))
    }

    @Test
    fun `a bundle that is not there, not shown yet, or of another bunch is not found`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val (otherOwner, otherBunch) = bunchWithOwner()
        saveLunch(bunchId, ownerId)
        saveLunch(otherBunch, otherOwner)
        val id = bundleId(bunchId)
        val ofOther = bundleId(otherBunch)

        assertThatThrownBy { reader.photos(ownerId, bunchId, 987_654_321L) }.isInstanceOf(BusinessException::class.java)
        assertThatThrownBy { reader.photos(ownerId, bunchId, ofOther) }.isInstanceOf(BusinessException::class.java)
        jdbc.update(
            "UPDATE recommended_bundles SET visible_from = ? WHERE id = ?",
            Timestamp.from(Instant.now().plusSeconds(3600)),
            id
        )
        assertThatThrownBy { reader.photos(ownerId, bunchId, id) }.isInstanceOf(BusinessException::class.java)
    }

    // --- the taking of a bundle by a draft (4.1.3, 4.1.4, 4.2.9 rule 5) ---

    @Test
    fun `a bundle that a draft is made from is deleted, its photos are noted as used, and it gives its title`() {
        val (ownerId, bunchId) = bunchWithOwner()
        saveLunch(bunchId, ownerId)
        val id = bundleId(bunchId)

        val used = inTransaction { reader.use(bunchId, id) }

        assertThat(used!!.title).isEqualTo("10월 19일 오전")
        assertThat(used.startDate).isEqualTo(LocalDate.parse("2026-10-19"))
        assertThat(bundles.findByBunchId(bunchId)).isEmpty()
        assertThat(count("recommendation_used_photos", "bunch_id = $bunchId")).isEqualTo(8)
        assertThat(count("photos", "bunch_id = $bunchId")).isEqualTo(8)
        assertThat(inTransaction { reader.use(bunchId, id) }).isNull()
    }

    @Test
    fun `a bundle of another bunch or one that is not shown yet is not taken`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val (_, otherBunch) = bunchWithOwner()
        saveLunch(bunchId, ownerId)
        val id = bundleId(bunchId)

        assertThat(inTransaction { reader.use(otherBunch, id) }).isNull()
        jdbc.update(
            "UPDATE recommended_bundles SET visible_from = ? WHERE id = ?",
            Timestamp.from(Instant.now().plusSeconds(3600)),
            id
        )
        assertThat(inTransaction { reader.use(bunchId, id) }).isNull()
        assertThat(bundles.findByBunchId(bunchId)).hasSize(1)
        assertThat(count("recommendation_used_photos", "bunch_id = $bunchId")).isZero()
    }

    // --- the old recommended bundles that stop being calculated while the switch is on ---

    @Test
    fun `the old recommended bundles are not calculated or deleted, and their changes are not noted`() {
        val (_, bunchId) = bunchWithOwner()

        inTransaction { service.noteChange(PhotoChangedEvent(bunchId, LocalDate.parse("2026-10-19"), true)) }

        assertThat(count("recommendation_dirty_dates", "bunch_id = $bunchId")).isZero()
        assertThat(scheduler.runOnce(Instant.now())).isZero()
        assertThat(scheduler.runExpiry(Instant.now())).isZero()
    }

    // --- helpers ---

    private fun <T> inTransaction(block: () -> T): T? = TransactionTemplate(transactions).execute { block() }

    private fun count(table: String, where: String): Int =
        jdbc.queryForObject("SELECT count(*) FROM $table WHERE $where", Int::class.java)!!

    private fun bundleId(bunchId: Long, date: String? = null): Long = jdbc.queryForObject(
        "SELECT id FROM recommended_bundles WHERE bunch_id = ?" + (date?.let { " AND day_key = DATE '$it'" } ?: ""),
        Long::class.java,
        bunchId
    )!!

    // The photos on the card of a bundle in the order of the card.
    private fun cardPhotoIds(bundleId: Long): List<Long> = jdbc.queryForList(
        "SELECT photo_id FROM recommended_bundle_photos WHERE bundle_id = ? AND card_position IS NOT NULL " +
            "ORDER BY card_position",
        Long::class.java,
        bundleId
    )

    private fun setCover(bunchId: Long, photoId: Long) {
        jdbc.update("UPDATE recommended_bundles SET cover_photo_id = ? WHERE bunch_id = ?", photoId, bunchId)
    }

    // A lunch of [count] photos, one every ten minutes, uploaded by the owner and another member in turn, calculated
    // and saved as the recommended bundle of the day. Returns the photos and the other member.
    private fun saveLunch(
        bunchId: Long,
        ownerId: Long,
        date: String = "2026-10-19",
        from: String = "10:00",
        count: Int = 8
    ): Pair<List<Long>, Long> {
        val member = joined(ownerId, bunchId)
        val photos = lunchPhotos(bunchId, ownerId, member, date, from, count)
        save(bunchId, photos)
        return photos.map { it.photoId } to member
    }

    private fun lunchPhotos(
        bunchId: Long,
        ownerId: Long,
        member: Long,
        date: String,
        from: String,
        count: Int
    ): List<RecoPhoto> = (0 until count).map { i ->
        val time = LocalTime.parse(from).plusMinutes(10L * i).toString()
        val uploader = if (i % 2 == 0) ownerId else member
        val id = insertPhoto(bunchId, date, time, uploaders = listOf(uploader))
        shot(id, time, (i % 2 + 1).toLong(), activity = ActivityCode.MEAL, date = date)
    }

    // The bundles of all the photos of the bunch are saved together, as a calculation of the bunch does.
    private fun save(bunchId: Long, photos: List<RecoPhoto>) {
        store.replace(bunchId, BundleCombiner(clock = CLOCK).combine(photos), calculatedAt)
    }
}
