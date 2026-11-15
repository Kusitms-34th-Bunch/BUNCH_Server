package com.bunch.kok

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.dto.BundleActor
import com.bunch.bundle.dto.BundleLocationInput
import com.bunch.bundle.dto.CreateBundleRequest
import com.bunch.bundle.service.BundleLocationService
import com.bunch.bundle.service.BundleService
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
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KokNullableAddressListIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var tokens: TokenProvider

    @Autowired
    private lateinit var sessions: RefreshTokenRepository

    @Autowired
    private lateinit var bundleService: BundleService

    @Autowired
    private lateinit var locations: BundleLocationService

    @Autowired
    private lateinit var bundles: BundleRepository

    @Autowired
    private lateinit var bunchRows: BunchRepository

    @Autowired
    private lateinit var transactions: PlatformTransactionManager

    private val ownSessions = mutableListOf<Pair<String, Long>>()
    private val bundleIds = mutableListOf<Long>()
    private val photoIds = mutableListOf<Long>()
    private val imageIds = mutableListOf<Long>()
    private val advertisementIds = mutableListOf<Long>()

    @AfterEach
    fun cleanNullablePlaceFixtures() {
        ownSessions.forEach { (sessionId, memberId) -> sessions.delete(sessionId, memberId) }
        bundleIds.forEach { jdbc.update("DELETE FROM bundles WHERE id = ?", it) }
        photoIds.forEach { jdbc.update("DELETE FROM photos WHERE id = ?", it) }
        imageIds.forEach { jdbc.update("DELETE FROM image_assets WHERE id = ?", it) }
        advertisementIds.forEach { jdbc.update("DELETE FROM feed_advertisements WHERE id = ?", it) }
    }

    @Test
    fun `bundle region address is explicit null in pin sheet and all or first registrant lists`() {
        val f = fixture()
        val authorization = bearer(f.actor)
        val original = pinBundle(f, authorization)
        assertNullAddress(original, "여의도")
        assertThat(original.path("sourceType").asText()).isEqualTo("BUNDLE")

        val sheet = read("/api/v1/bundles/${f.bundle}/pinned-places", authorization)
        val place = sheet.path("locations").single()
        assertNullAddress(place, "여의도")
        assertThat(place.path("pinned").asBoolean()).isTrue()
        assertThat(place.path("pinId").asLong()).isEqualTo(original.path("id").asLong())

        val colleague = bearer(f.colleague)
        listOf(null, f.actor).forEach { memberId ->
            val result = list(colleague, memberId)
            assertThat(result.path("bunchId").asLong()).isEqualTo(f.bunch)
            assertThat(result.path("totalCount").asLong()).isEqualTo(1L)
            val card = result.path("items").single()
            assertNullAddress(card, "여의도")
            assertThat(card.path("id")).isEqualTo(original.path("id"))
            assertThat(card.path("pinnedByMemberId").asLong()).isEqualTo(f.actor)
            assertThat(card.path("sourceAccessible").asBoolean()).isTrue()
            assertThat(card.path("sourceBundleId").asLong()).isEqualTo(f.bundle)
            assertThat(result.path("members").single { it.path("memberId").asLong() == f.actor }
                .path("count").asLong()).isEqualTo(1L)
            assertThat(result.path("members").single { it.path("memberId").asLong() == f.colleague }
                .path("count").asLong()).isZero()
        }
        val zero = list(colleague, f.colleague)
        assertThat(zero.path("selectedMemberId").asLong()).isEqualTo(f.colleague)
        assertThat(zero.path("items").size()).isZero()
    }

    @Test
    fun `advertisement region address is explicit null in catalog pin response and member lists`() {
        val actor = member()
        val bunch = create(actor).id
        val colleague = member()
        join(colleague, issue(actor, bunch))
        val advertisement = jdbc.queryForObject(
            """INSERT INTO feed_advertisements
                (name, introduction, photo_url, place_provider, place_external_id, place_name, place_address)
                VALUES ('BUNCH SPOT', '지역 소개', 'https://example.invalid/region.jpg',
                    'kakao', 'ad-region', '성수동', NULL) RETURNING id""",
            Long::class.java
        )!!
        advertisementIds.add(advertisement)
        val authorization = bearer(colleague)
        val path = "/api/v1/feed/advertisements/$advertisement"
        assertNullAddress(read(path, authorization).path("place"), "성수동")

        val pinned = pin("$path/pin", authorization)
        assertNullAddress(pinned, "성수동")
        assertThat(pinned.path("sourceAdvertisementId").asLong()).isEqualTo(advertisement)
        val catalog = read(path, authorization)
        assertNullAddress(catalog.path("place"), "성수동")
        assertThat(catalog.path("pinned").asBoolean()).isTrue()
        assertThat(catalog.path("pinId")).isEqualTo(pinned.path("id"))

        val viewer = bearer(actor)
        listOf(null, colleague).forEach { memberId ->
            val result = list(viewer, memberId)
            assertThat(result.path("totalCount").asLong()).isEqualTo(1L)
            val card = result.path("items").single()
            assertNullAddress(card, "성수동")
            assertThat(card.path("id")).isEqualTo(pinned.path("id"))
            assertThat(card.path("pinnedByMemberId").asLong()).isEqualTo(colleague)
            assertThat(card.path("sourceType").asText()).isEqualTo("ADVERTISEMENT")
            assertNoSource(card)
        }
        assertThat(list(viewer, actor).path("items").size()).isZero()
    }

    @Test
    fun `private then deleted source retains null address and first pin metadata without source disclosure`() {
        val f = fixture()
        val authorization = bearer(f.actor)
        val original = pinBundle(f, authorization)
        jdbc.update("UPDATE bundles SET visibility = 'PRIVATE' WHERE id = ?", f.bundle)
        assertUnavailableSnapshot(f, authorization, original)

        bundleService.delete(
            BundleActor(f.owner, "nullable-source-owner"),
            f.sourceBunch,
            f.bundle,
            UUID.randomUUID().toString()
        )
        assertUnavailableSnapshot(f, authorization, original)
    }

    private fun assertUnavailableSnapshot(f: Fixture, authorization: String, original: JsonNode) {
        mvc.perform(get("/api/v1/bundles/${f.bundle}/pinned-places").header("Authorization", authorization))
            .andExpect(status().isNotFound)
        listOf(null, f.actor).forEach { memberId ->
            val result = list(authorization, memberId)
            assertThat(result.path("totalCount").asLong()).isEqualTo(1L)
            val card = result.path("items").single()
            assertNullAddress(card, "여의도")
            listOf("id", "provider", "externalId", "pinnedByMemberId", "pinnedAt", "sourceType").forEach {
                assertThat(card.path(it)).isEqualTo(original.path(it))
            }
            assertNoSource(card)
        }
    }

    private fun assertNullAddress(place: JsonNode, name: String) {
        assertThat(place.path("name").asText()).isEqualTo(name)
        assertThat(place.has("address")).isTrue()
        assertThat(place.path("address").isNull).isTrue()
    }

    private fun assertNoSource(card: JsonNode) {
        assertThat(card.path("sourceAccessible").asBoolean()).isFalse()
        listOf("sourceBundleId", "sourceBunchId", "sourceBunchName", "sourceBundleTitle").forEach {
            assertThat(card.has(it)).isTrue()
            assertThat(card.path(it).isNull).isTrue()
        }
    }

    private fun list(authorization: String, memberId: Long?): JsonNode =
        read("/api/v1/pinned-places" + (memberId?.let { "?memberId=$it" } ?: ""), authorization)

    private fun read(path: String, authorization: String): JsonNode {
        val result = mvc.perform(get(path).header("Authorization", authorization))
            .andExpect(status().isOk)
            .andReturn()
        return mapper.readTree(result.response.contentAsByteArray).path("data")
    }

    private fun pin(path: String, authorization: String): JsonNode {
        val result = mvc.perform(put(path).header("Authorization", authorization))
            .andExpect(status().isOk)
            .andReturn()
        return mapper.readTree(result.response.contentAsByteArray).path("data")
    }

    private fun pinBundle(f: Fixture, authorization: String): JsonNode =
        pin("/api/v1/bundles/${f.bundle}/locations/${f.location}/pin", authorization)

    private fun bearer(memberId: Long): String {
        val sessionId = UUID.randomUUID().toString()
        sessions.save(sessionId, memberId, "nullable-place-test-refresh", 3600)
        ownSessions.add(sessionId to memberId)
        return "Bearer ${tokens.createAccessToken(memberId, "ROLE_USER", sessionId)}"
    }

    private fun fixture(): Fixture {
        val actor = member()
        val bunch = create(actor).id
        val colleague = member()
        join(colleague, issue(actor, bunch))
        val owner = member()
        val sourceBunch = create(owner).id
        val selected = member()
        join(selected, issue(owner, sourceBunch))
        jdbc.update("UPDATE bunches SET visibility = 'PUBLIC' WHERE id = ?", sourceBunch)
        val image = jdbc.queryForObject(
            """INSERT INTO image_assets(public_id, url, created_at)
                VALUES (?, 'https://example.invalid/nullable-place.jpg', CURRENT_TIMESTAMP) RETURNING id""",
            Long::class.java,
            "nullable-kok/${UUID.randomUUID()}"
        )!!
        imageIds.add(image)
        val photo = jdbc.queryForObject(
            """INSERT INTO photos(bunch_id, image_asset_id, file_hash, processing_status, date_source, created_at)
                VALUES (?, ?, ?, 'READY', 'UNKNOWN', CURRENT_TIMESTAMP) RETURNING id""",
            Long::class.java,
            sourceBunch,
            image,
            UUID.randomUUID().toString().replace("-", "").repeat(2)
        )!!
        photoIds.add(photo)
        jdbc.update(
            """INSERT INTO photo_uploaders(photo_id, member_id, upload_batch_id, uploaded_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)""",
            photo,
            owner,
            UUID.randomUUID()
        )
        val draft = bundleService.create(
            BundleActor(owner, "nullable-source-owner"),
            sourceBunch,
            UUID.randomUUID(),
            CreateBundleRequest(photoIds = listOf(photo), participantMemberIds = listOf(selected))
        )
        bundleIds.add(draft.id)
        val stored = TransactionTemplate(transactions).execute {
            bunchRows.findLocked(sourceBunch)
            locations.replaceLocked(
                bundles.findLocked(draft.id)!!,
                listOf(BundleLocationInput("kakao", "bundle-region", "여의도", null, 0))
            ).locations.single()
        }!!
        // Published source setup follows KokIntegrationTest; location and Kok writes use their services.
        jdbc.update(
            """UPDATE bundles SET status = 'PUBLISHED', visibility = 'PUBLIC', title = '주소 없는 지역',
                published_at = CURRENT_TIMESTAMP WHERE id = ?""",
            draft.id
        )
        return Fixture(actor, colleague, bunch, owner, sourceBunch, draft.id, stored.id)
    }

    private data class Fixture(
        val actor: Long,
        val colleague: Long,
        val bunch: Long,
        val owner: Long,
        val sourceBunch: Long,
        val bundle: Long,
        val location: Long
    )
}
