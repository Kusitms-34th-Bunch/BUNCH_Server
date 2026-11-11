package com.bunch.feed

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.feed.domain.AdvertisementRepository
import com.bunch.feed.service.AdvertisementService
import com.bunch.global.exception.ErrorCode
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import com.bunch.kok.service.KokService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdvertisementIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var advertisements: AdvertisementService

    @Autowired
    private lateinit var rows: AdvertisementRepository

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var tokens: TokenProvider

    @Autowired
    private lateinit var sessions: RefreshTokenRepository

    @Autowired
    private lateinit var transactions: PlatformTransactionManager

    @Autowired
    private lateinit var pins: KokService

    private val ownSessions = mutableListOf<Pair<String, Long>>()

    @AfterEach
    fun cleanAdvertisements() {
        ownSessions.forEach { (sessionId, memberId) -> sessions.delete(sessionId, memberId) }
        ownSessions.clear()
        jdbc.update("DELETE FROM feed_advertisements")
    }

    @Test
    fun `catalog reads active member eligible rows and reflects operational edits immediately`() {
        val visitor = member()
        val first = advertisement()
        val second = advertisement()
        val inactive = advertisement(active = false)
        assertThat(rows.findEligible(visitor).map { it.id }).containsExactly(first, second)
        advertisements.hide(visitor, first)
        assertThat(rows.findEligible(visitor).map { it.id }).containsExactly(second)
        jdbc.update("UPDATE feed_advertisements SET active = true, introduction = '새 소개' WHERE id = ?", inactive)
        assertThat(rows.findEligible(visitor).map { it.id }).containsExactly(second, inactive)
        assertThat(rows.findEligible(visitor).last().introduction).isEqualTo("새 소개")
        assertThat(rows.findEligible(visitor).last().tags).containsExactly("예약 가능", "4인")
        assertThat(rows.findEligible(visitor).last().externalLink).isNull()
        assertThat(rows.findEligible(visitor).last().place().provider).isEqualTo("kakao")
        jdbc.update("UPDATE feed_advertisements SET active = false WHERE id = ?", second)
        assertThat(rows.findEligible(visitor).map { it.id }).containsExactly(inactive)
    }

    @Test
    fun `personal hide survives bunch switching and content edits without affecting another member`() {
        val actor = member()
        val bunch = create(actor).id
        val colleague = member()
        join(colleague, issue(actor, bunch))
        val ad = advertisement()
        advertisements.hide(actor, ad)
        advertisements.hide(actor, ad)
        assertThat(count("feed_advertisement_hides")).isEqualTo(1)
        assertThat(rows.findEligible(actor)).isEmpty()
        assertThat(rows.findEligible(colleague).map { it.id }).containsExactly(ad)
        create(actor)
        jdbc.update("UPDATE feed_advertisements SET name = '갱신', active = false WHERE id = ?", ad)
        advertisements.hide(actor, ad)
        jdbc.update("UPDATE feed_advertisements SET active = true WHERE id = ?", ad)
        assertThat(rows.findEligible(actor)).isEmpty()
        assertThat(rows.findEligible(colleague).single().name).isEqualTo("갱신")
    }

    @Test
    fun `concurrent hides persist once and zero membership users can hide inactive ads`() {
        val visitor = member()
        val ad = advertisement(active = false)
        concurrently(List(6) { { advertisements.hide(visitor, ad) } }).forEach { it.getOrThrow() }
        assertThat(count("feed_advertisement_hides")).isEqualTo(1)
        assertThat(rows.isHidden(visitor, ad)).isTrue()
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { advertisements.hide(visitor, Long.MAX_VALUE) }
        assertError(ErrorCode.INVALID_INPUT) { advertisements.hide(visitor, 0) }
        assertError(ErrorCode.SIGNUP_INCOMPLETE) { advertisements.hide(member(false), ad) }
        jdbc.update("UPDATE members SET account_status = 'WITHDRAWN' WHERE id = ?", visitor)
        assertError(ErrorCode.UNAUTHORIZED) { advertisements.hide(visitor, ad) }
    }

    @Test
    fun `HTTP hide enforces session signup and positive target while remaining idempotent`() {
        val visitor = member()
        val ad = advertisement()
        val path = "/api/v1/feed/advertisements/$ad/hide"
        mvc.perform(put(path)).andExpect(status().isUnauthorized)
        val bearer = bearer(visitor)
        repeat(2) {
            mvc.perform(put(path).header("Authorization", bearer))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isEmpty)
        }
        mvc.perform(put("/api/v1/feed/advertisements/0/hide").header("Authorization", bearer))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("COMMON_001"))
        mvc.perform(put("/api/v1/feed/advertisements/9223372036854775807/hide")
            .header("Authorization", bearer)).andExpect(status().isNotFound)
        mvc.perform(put(path).header("Authorization", bearer(member(false))))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("AUTH_015"))
    }

    @Test
    fun `hide participates in rollback rather than permanently hiding failed transactions`() {
        val visitor = member()
        val ad = advertisement()
        assertThatThrownBy {
            TransactionTemplate(transactions).executeWithoutResult {
                advertisements.hide(visitor, ad)
                error("rollback")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(rows.isHidden(visitor, ad)).isFalse()
        assertThat(rows.findEligible(visitor).map { it.id }).containsExactly(ad)
    }

    @Test
    fun `batch fills supplied offsets from eligible DB pool without replacement and tracks last shown ad`() {
        val visitor = member()
        val first = advertisement(externalId = "first")
        val second = advertisement(externalId = "second")
        val third = advertisement(externalId = "third")
        val hidden = advertisement(externalId = "hidden")
        advertisement(active = false)
        advertisements.hide(visitor, hidden)
        val batch = advertisements.selectForFeed(visitor, 18, listOf(6, 12, 18), first)
        assertThat(batch.placements.map { it.afterBundle }).containsExactly(6, 12, 18)
        assertThat(batch.placements.map { it.advertisement.id }).containsExactlyInAnyOrder(first, second, third)
        assertThat(batch.placements.first().advertisement.id).isNotEqualTo(first)
        assertThat(batch.lastAdId).isEqualTo(batch.placements.last().advertisement.id)
        assertThat(batch.placements).allSatisfy {
            assertThat(it.advertisement.canPin).isFalse()
            assertThat(it.advertisement.pinned).isFalse()
            assertThat(it.advertisement.pinId).isNull()
        }
        val empty = advertisements.selectForFeed(visitor, 0, emptyList(), batch.lastAdId)
        assertThat(empty.placements).isEmpty()
        assertThat(empty.lastAdId).isEqualTo(batch.lastAdId)
        val noSlot = advertisements.selectForFeed(visitor, 5, emptyList(), batch.lastAdId)
        assertThat(noSlot.placements).isEmpty()
        assertThat(noSlot.lastAdId).isEqualTo(batch.lastAdId)
    }

    @Test
    fun `single remaining ad skips later slots and next requests refresh eligibility from DB`() {
        val visitor = member()
        val first = advertisement()
        val response = advertisements.selectForFeed(visitor, 18, listOf(6, 12, 18), null)
        assertThat(response.placements.map { it.afterBundle }).containsExactly(6)
        assertThat(response.lastAdId).isEqualTo(first)
        assertThat(advertisements.selectForFeed(visitor, 6, listOf(6), first).placements).isEmpty()
        advertisements.hide(visitor, first)
        assertThat(advertisements.selectForFeed(visitor, 6, listOf(6), null).placements).isEmpty()
        val next = advertisement(externalId = "new")
        val refreshed = advertisements.selectForFeed(visitor, 6, listOf(6), first)
        assertThat(refreshed.placements.single().advertisement.id).isEqualTo(next)
        assertThat(refreshed.lastAdId).isEqualTo(next)
        jdbc.update("UPDATE feed_advertisements SET active = false WHERE id = ?", next)
        val skipped = advertisements.selectForFeed(visitor, 6, listOf(6), first)
        assertThat(skipped.placements).isEmpty()
        assertThat(skipped.lastAdId).isEqualTo(first)
    }

    @Test
    fun `batch rejects malformed positions and incomplete or inactive viewers even without slots`() {
        val visitor = member()
        listOf(listOf(0), listOf(7), listOf(6, 6), listOf(6, 3)).forEach { slots ->
            assertError(ErrorCode.INVALID_INPUT) { advertisements.selectForFeed(visitor, 6, slots, null) }
        }
        assertError(ErrorCode.INVALID_INPUT) { advertisements.selectForFeed(visitor, 51, emptyList(), null) }
        assertError(ErrorCode.INVALID_INPUT) { advertisements.selectForFeed(visitor, -1, emptyList(), null) }
        assertError(ErrorCode.INVALID_INPUT) { advertisements.selectForFeed(visitor, 6, listOf(6), 0) }
        assertError(ErrorCode.SIGNUP_INCOMPLETE) {
            advertisements.selectForFeed(member(false), 0, emptyList(), null)
        }
        jdbc.update("UPDATE members SET account_status = 'WITHDRAWN' WHERE id = ?", visitor)
        assertError(ErrorCode.UNAUTHORIZED) { advertisements.selectForFeed(visitor, 0, emptyList(), null) }
    }

    @Test
    fun `actual G pin is shared with another member and advertisements for same place then follows current bunch`() {
        val actor = member()
        val bunch = create(actor).id
        val colleague = member()
        join(colleague, issue(actor, bunch))
        val first = advertisement()
        val samePlace = advertisement()
        val different = advertisement(externalId = "different")
        val original = advertisements.pin(actor, first)
        val duplicate = advertisements.pin(colleague, samePlace)
        assertThat(duplicate).isEqualTo(original)
        assertThat(original.sourceAdvertisementId).isEqualTo(first)
        assertThat(original.pinnedByMemberId).isEqualTo(actor)
        val response = advertisements.selectForFeed(colleague, 18, listOf(6, 12, 18), null)
        assertThat(response.placements).hasSize(3)
        response.placements.forEach {
            val card = it.advertisement
            assertThat(card.canPin).isTrue()
            assertThat(card.pinned).isEqualTo(card.id != different)
            assertThat(card.pinId).isEqualTo(if (card.id == different) null else original.id)
        }
        advertisements.hide(actor, first)
        assertThat(advertisements.read(colleague, first).pinId).isEqualTo(original.id)
        pins.unpin(colleague, original.id)
        assertThat(advertisements.read(colleague, samePlace).pinned).isFalse()
        val repinned = advertisements.pin(colleague, samePlace)
        assertThat(repinned.id).isNotEqualTo(original.id)
        create(colleague)
        assertThat(advertisements.read(colleague, samePlace).pinned).isFalse()
        assertThat(advertisements.read(actor, samePlace).pinId).isEqualTo(repinned.id)
    }

    @Test
    fun `bundle pin and advertisement pin reuse the real provider external ID identity without overwriting source`() {
        val actor = member()
        val bunch = create(actor).id
        val bundle = jdbc.queryForObject(
            """INSERT INTO bundles
                (bunch_id, created_by, status, title, visibility, created_at, updated_at, published_at)
                VALUES (?, ?, 'PUBLISHED', '번들', 'PUBLIC', now(), now(), now()) RETURNING id""",
            Long::class.java, bunch, actor
        )!!
        val location = jdbc.queryForObject(
            """INSERT INTO bundle_locations (bundle_id, provider, external_id, name, address, position)
                VALUES (?, 'kakao', 'studio', '번들 원래 장소명', '원래 주소', 0) RETURNING id""",
            Long::class.java, bundle
        )!!
        val original = pins.pinBundleLocation(actor, bundle, location)
        val ad = advertisement()
        assertThat(advertisements.read(actor, ad).pinId).isEqualTo(original.id)
        assertThat(advertisements.pin(actor, ad)).isEqualTo(original)
        assertThat(count("pinned_places")).isEqualTo(1)
    }

    @Test
    fun `pin rejects invisible and missing ads and zero membership but permits another unhidden member`() {
        val visitor = member()
        val active = advertisement()
        assertThat(advertisements.read(visitor, active).canPin).isFalse()
        assertError(ErrorCode.CURRENT_BUNCH_REQUIRED) { advertisements.pin(visitor, active) }
        val actor = member()
        create(actor)
        val inactive = advertisement(active = false)
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { advertisements.pin(actor, inactive) }
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { advertisements.read(actor, inactive) }
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { advertisements.pin(actor, Long.MAX_VALUE) }
        assertError(ErrorCode.INVALID_INPUT) { advertisements.pin(actor, -1) }
        advertisements.hide(actor, active)
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { advertisements.pin(actor, active) }
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { advertisements.read(actor, active) }
        create(visitor)
        assertThat(advertisements.pin(visitor, active).sourceAdvertisementId).isEqualTo(active)
    }

    @Test
    fun `concurrent pins retain one original record and concurrent personal hide cannot deadlock`() {
        val actor = member()
        val bunch = create(actor).id
        val colleague = member()
        join(colleague, issue(actor, bunch))
        val ad = advertisement()
        val results = concurrently(List(6) { index ->
            { advertisements.pin(if (index % 2 == 0) actor else colleague, ad) }
        }).map { it.getOrThrow() }
        assertThat(results.map { it.id }.distinct()).hasSize(1)
        assertThat(count("pinned_places")).isEqualTo(1)
        val outcomes = concurrently(listOf(
            { runCatching { advertisements.pin(actor, ad) } },
            { runCatching { advertisements.hide(actor, ad) } }
        )).map { it.getOrThrow() }
        assertThat(outcomes[1].isSuccess).isTrue()
        outcomes[0].exceptionOrNull()?.let {
            assertThat(it).isInstanceOf(com.bunch.global.exception.BusinessException::class.java)
            assertThat((it as com.bunch.global.exception.BusinessException).errorCode)
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND)
        }
        assertThat(rows.isHidden(actor, ad)).isTrue()
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { advertisements.pin(actor, ad) }
    }

    @Test
    fun `deleting real advertisement preserves pin snapshots while cascading its personal hides`() {
        val actor = member()
        create(actor)
        val ad = advertisement()
        val pin = advertisements.pin(actor, ad)
        advertisements.hide(actor, ad)
        jdbc.update("DELETE FROM feed_advertisements WHERE id = ?", ad)
        assertThat(count("feed_advertisement_hides")).isZero()
        assertThat(count("pinned_places")).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT name FROM pinned_places WHERE id = ?", String::class.java, pin.id))
            .isEqualTo("실제 공방")
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { advertisements.pin(actor, ad) }
        pins.unpin(actor, pin.id)
        assertThat(count("pinned_places")).isZero()
    }

    @Test
    fun `HTTP pin reads stored place ignores forged body and returns current pin state`() {
        val actor = member()
        create(actor)
        val ad = advertisement()
        val path = "/api/v1/feed/advertisements/$ad"
        val bearer = bearer(actor)
        mvc.perform(put("$path/pin")).andExpect(status().isUnauthorized)
        mvc.perform(put("$path/pin").header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"provider":"forged","externalId":"forged","name":"위조","address":"위조"}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.provider").value("kakao"))
            .andExpect(jsonPath("$.data.externalId").value("studio"))
            .andExpect(jsonPath("$.data.name").value("실제 공방"))
            .andExpect(jsonPath("$.data.sourceAdvertisementId").value(ad))
        mvc.perform(get(path).header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value(ad))
            .andExpect(jsonPath("$.data.place.address").value("서울 성동구"))
            .andExpect(jsonPath("$.data.tags[0]").value("예약 가능"))
            .andExpect(jsonPath("$.data.pinned").value(true))
            .andExpect(jsonPath("$.data.canPin").value(true))
        val visitor = bearer(member())
        mvc.perform(get(path).header("Authorization", visitor))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.canPin").value(false))
        mvc.perform(put("$path/pin").header("Authorization", visitor))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("BUNCH_010"))
        advertisements.hide(actor, ad)
        mvc.perform(put("$path/pin").header("Authorization", bearer)).andExpect(status().isNotFound)
        mvc.perform(get(path).header("Authorization", bearer)).andExpect(status().isNotFound)
    }

    private fun bearer(memberId: Long): String {
        val pair = tokens.generateToken(memberId, "ROLE_USER")
        val sessionId = tokens.getSessionId(pair.accessToken)
        sessions.save(sessionId, memberId, pair.refreshToken, tokens.getRefreshTokenValiditySeconds())
        ownSessions.add(sessionId to memberId)
        return "Bearer ${pair.accessToken}"
    }

    private fun advertisement(active: Boolean = true, externalId: String = "studio"): Long = jdbc.queryForObject(
        """INSERT INTO feed_advertisements
            (name, introduction, photo_url, tags, place_provider, place_external_id, place_name, place_address, active)
            VALUES ('공방', '소개', 'https://example.com/photo.jpg', '["예약 가능", "4인"]'::jsonb,
                'kakao', ?, '실제 공방', '서울 성동구', ?) RETURNING id""",
        Long::class.java,
        externalId,
        active
    )!!
}
