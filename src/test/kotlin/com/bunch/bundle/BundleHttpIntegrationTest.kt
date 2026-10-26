package com.bunch.bundle

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.domain.Visibility
import com.bunch.bundle.domain.BundleStatus
import com.bunch.bundle.dto.BundleActor
import com.bunch.bundle.dto.BundleParticipantRequest
import com.bunch.bundle.dto.BundleTextRequest
import com.bunch.bundle.dto.PublishBundleRequest
import com.bunch.bundle.service.BundleLeaseService
import com.bunch.global.exception.ErrorCode
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import com.bunch.image.domain.ImageAsset
import com.bunch.image.domain.ImageAssetRepository
import com.bunch.photo.domain.DateSource
import com.bunch.photo.domain.Photo
import com.bunch.photo.domain.PhotoRepository
import com.bunch.photo.domain.PhotoUploader
import com.bunch.photo.domain.PhotoUploaderRepository
import com.bunch.photo.domain.ProcessingStatus
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BundleHttpIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var tokens: TokenProvider

    @Autowired
    private lateinit var sessions: RefreshTokenRepository

    @Autowired
    private lateinit var leaseService: BundleLeaseService

    @Autowired
    private lateinit var bunchRepository: BunchRepository

    @Autowired
    private lateinit var imageAssets: ImageAssetRepository

    @Autowired
    private lateinit var photos: PhotoRepository

    @Autowired
    private lateinit var photoUploaders: PhotoUploaderRepository

    private val sessionIds = mutableListOf<Pair<String, Long>>()
    private val ownedAssetIds = mutableListOf<Long>()

    @AfterEach
    fun cleanBundleReferencesBeforeBunchSupport() {
        // BunchIntegrationSupport removes bunches and members in its superclass hook.
        // Clear bundle rows and Photo fixtures first so RESTRICT FKs remain removable.
        jdbc.execute("DROP TRIGGER IF EXISTS bundle_test_reject_participant ON bundle_participants")
        jdbc.execute("DROP TRIGGER IF EXISTS bundle_test_reject_photo ON bundle_photos")
        jdbc.execute("DROP FUNCTION IF EXISTS bunch_test_reject_write()")
        jdbc.update("DELETE FROM bundle_edit_leases")
        jdbc.update("DELETE FROM bundle_comments")
        jdbc.update("DELETE FROM bundle_participants")
        jdbc.update("DELETE FROM bundle_photos")
        jdbc.update("DELETE FROM bundle_requests")
        jdbc.update("DELETE FROM bundles")
        jdbc.update("DELETE FROM photo_uploaders")
        jdbc.update("DELETE FROM photos")
        ownedAssetIds.forEach { jdbc.update("DELETE FROM image_assets WHERE id = ?", it) }
        ownedAssetIds.clear()

        sessionIds.forEach { (sessionId, memberId) -> sessions.delete(sessionId, memberId) }
        sessionIds.clear()
    }

    @Test
    fun `create validates headers malformed JSON and idempotency fingerprints`() {
        val fixture = fixture(photoCount = 2)
        val body = createBody(listOf(fixture.photoIds.first()))

        createRequest(fixture.owner, fixture.bunchId, body, idempotencyKey = null)
            .expectError(ErrorCode.INVALID_INPUT)
        createRequest(fixture.owner, fixture.bunchId, body, idempotencyKey = "not-a-uuid")
            .expectError(ErrorCode.INVALID_INPUT)
        createRequest(fixture.owner, fixture.bunchId, "{", idempotencyKey = UUID.randomUUID().toString())
            .expectError(ErrorCode.INVALID_JSON)

        val key = UUID.randomUUID().toString()
        val first = createDraft(fixture.owner, fixture.bunchId, fixture.photoIds.first(), key)
        val replay = createDraft(fixture.owner, fixture.bunchId, fixture.photoIds.first(), key)

        assertThat(replay.path("id").asLong()).isEqualTo(first.path("id").asLong())
        assertThat(replay.path("status").asText()).isEqualTo(BundleStatus.DRAFT.name)

        createRequest(
            fixture.owner,
            fixture.bunchId,
            createBody(listOf(fixture.photoIds[1])),
            idempotencyKey = key
        ).expectError(ErrorCode.IDEMPOTENCY_CONFLICT)

        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM bundles WHERE bunch_id = ?",
                Long::class.java,
                fixture.bunchId
            )
        ).isEqualTo(1L)
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM bundle_requests WHERE bunch_id = ?",
                Long::class.java,
                fixture.bunchId
            )
        ).isEqualTo(1L)
    }

    @Test
    fun `authenticated reads return paged list detail and summary contracts`() {
        val fixture = fixture(photoCount = 2)
        val first = createDraft(fixture.owner, fixture.bunchId, fixture.photoIds.first())
        createDraft(fixture.owner, fixture.bunchId, fixture.photoIds[1])

        mvc.perform(
            get(bundlesPath(fixture.bunchId))
                .header(HttpHeaders.AUTHORIZATION, bearer(fixture.owner))
                .param("status", BundleStatus.DRAFT.name)
                .param("page", "0")
                .param("size", "1")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].status").value(BundleStatus.DRAFT.name))
            .andExpect(jsonPath("$.data.page").value(0))
            .andExpect(jsonPath("$.data.size").value(1))
            .andExpect(jsonPath("$.data.total").value(2))

        mvc.perform(
            get(bundlePath(fixture.bunchId, first.path("id").asLong()))
                .header(HttpHeaders.AUTHORIZATION, bearer(fixture.owner))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value(first.path("id").asLong()))
            .andExpect(jsonPath("$.data.status").value(BundleStatus.DRAFT.name))
            .andExpect(jsonPath("$.data.photos.length()").value(1))
            .andExpect(jsonPath("$.data.participants.length()").value(2))

        mvc.perform(
            get(summaryPath(fixture.bunchId))
                .header(HttpHeaders.AUTHORIZATION, bearer(fixture.owner))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.draftCount").value(2))
            .andExpect(jsonPath("$.data.publishedCount").value(0))
            .andExpect(jsonPath("$.data.stage").value(1))
    }

    @Test
    fun `draft title requires the lease actor session screen and token`() {
        val fixture = fixture(photoCount = 1)
        val bundle = createDraft(fixture.owner, fixture.bunchId, fixture.photoIds.single())
        val bundleId = bundle.path("id").asLong()
        val screen = "bundle-editor"
        val grant = leaseService.acquire(
            actor = BundleActor(fixture.owner.memberId, fixture.owner.sessionId, screen),
            bunchId = fixture.bunchId,
            bundleId = bundleId,
            target = "TITLE",
            connectionId = "http-$bundleId"
        )

        val wrongSession = sessionFor(fixture.owner.memberId)
        titleRequest(
            wrongSession,
            fixture.bunchId,
            bundleId,
            screen = screen,
            value = "wrong sid",
            expectedVersion = 0,
            leaseToken = grant.token
        ).expectError(ErrorCode.BUNDLE_LEASE_CONFLICT)

        titleRequest(
            fixture.owner,
            fixture.bunchId,
            bundleId,
            screen = "another-screen",
            value = "wrong screen",
            expectedVersion = 0,
            leaseToken = grant.token
        ).expectError(ErrorCode.BUNDLE_LEASE_CONFLICT)

        titleRequest(
            fixture.owner,
            fixture.bunchId,
            bundleId,
            screen = screen,
            value = "wrong token",
            expectedVersion = 0,
            leaseToken = UUID.randomUUID()
        ).expectError(ErrorCode.BUNDLE_LEASE_CONFLICT)

        titleRequest(
            fixture.owner,
            fixture.bunchId,
            bundleId,
            screen = screen,
            value = "첫 번째 제목",
            expectedVersion = 0,
            leaseToken = grant.token
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.title").value("첫 번째 제목"))
            .andExpect(jsonPath("$.data.titleVersion").value(1))

        titleRequest(
            fixture.owner,
            fixture.bunchId,
            bundleId,
            screen = screen,
            value = "오래된 제목",
            expectedVersion = 0,
            leaseToken = grant.token
        )
            .expectError(ErrorCode.BUNDLE_VERSION_CONFLICT)
            // BundleService exposes the latest field version directly in error.detail.
            .andExpect(jsonPath("$.error.detail.version").value(1))
            .andExpect(jsonPath("$.error.detail.value").value("첫 번째 제목"))

        titleRequest(
            fixture.owner,
            fixture.bunchId,
            bundleId,
            screen = screen,
            value = "잘못된\n제목",
            expectedVersion = 1,
            leaseToken = grant.token
        ).expectError(ErrorCode.INVALID_INPUT)
    }

    @Test
    fun `member and participant mutations cannot cross bunch boundaries`() {
        val fixture = fixture(photoCount = 1)
        val bundle = createDraft(fixture.owner, fixture.bunchId, fixture.photoIds.single())
        val foreign = account()
        create(foreign.memberId)

        mvc.perform(
            post("${bundlePath(fixture.bunchId, bundle.path("id").asLong())}/participants")
                .header(HttpHeaders.AUTHORIZATION, bearer(fixture.owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(mapOf("memberId" to foreign.memberId)))
        ).expectError(ErrorCode.FORBIDDEN)

        mvc.perform(
            get(bundlePath(fixture.bunchId, bundle.path("id").asLong()))
                .header(HttpHeaders.AUTHORIZATION, bearer(foreign))
        ).expectError(ErrorCode.FORBIDDEN)
    }

    @Test
    fun `published bundles reject late draft writes and allow manager and self comment updates`() {
        val fixture = fixture(photoCount = 1, participantUploads = false)
        val bundle = createDraft(fixture.owner, fixture.bunchId, fixture.photoIds.single())
        val bundleId = bundle.path("id").asLong()
        val added = mvc.perform(
            post("${bundlePath(fixture.bunchId, bundleId)}/participants")
                .header(HttpHeaders.AUTHORIZATION, bearer(fixture.owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(BundleParticipantRequest(fixture.participant.memberId)))
        )
            .andExpect(status().isOk)
            .andReturn()
            .data()
        val participantCommentVersion = added.path("comments")
            .first { it.path("memberId").asLong() == fixture.participant.memberId }
            .path("version")
            .asLong()
        assertThat(participantCommentVersion).isGreaterThan(0)
        val screen = "publish-editor"
        val grant = leaseService.acquire(
            actor = BundleActor(fixture.owner.memberId, fixture.owner.sessionId, screen),
            bunchId = fixture.bunchId,
            bundleId = bundleId,
            target = "TITLE",
            connectionId = "publish-$bundleId"
        )

        titleRequest(
            fixture.owner,
            fixture.bunchId,
            bundleId,
            screen = screen,
            value = "초안 제목",
            expectedVersion = 0,
            leaseToken = grant.token
        ).andExpect(status().isOk)

        visibilityRequest(
            fixture.owner,
            fixture.bunchId,
            bundleId,
            value = Visibility.PRIVATE,
            expectedVersion = 0,
            mode = BundleStatus.DRAFT
        ).andExpect(status().isOk)

        commentRequest(
            fixture.owner,
            fixture.bunchId,
            bundleId,
            value = "생성자 코멘트",
            expectedVersion = 0,
            mode = BundleStatus.DRAFT
        ).andExpect(status().isOk)
        val draftParticipantComment = commentRequest(
            fixture.participant,
            fixture.bunchId,
            bundleId,
            value = "참여자 코멘트",
            expectedVersion = participantCommentVersion,
            mode = BundleStatus.DRAFT
        )
            .andExpect(status().isOk)
            .andReturn()
            .data()
        val publishedParticipantCommentVersion = draftParticipantComment.path("comments")
            .first { it.path("memberId").asLong() == fixture.participant.memberId }
            .path("version")
            .asLong()

        val revision = detail(fixture.owner, fixture.bunchId, bundleId).path("revision").asLong()
        val published = mvc.perform(
            post("${bundlePath(fixture.bunchId, bundleId)}/publish")
                .header(HttpHeaders.AUTHORIZATION, bearer(fixture.owner))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(PublishBundleRequest(revision)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value(BundleStatus.PUBLISHED.name))
            .andReturn()
            .data()

        titleRequest(
            fixture.owner,
            fixture.bunchId,
            bundleId,
            screen = "rest",
            value = "늦은 초안 저장",
            expectedVersion = published.path("titleVersion").asLong(),
            leaseToken = grant.token,
            mode = BundleStatus.DRAFT
        ).expectError(ErrorCode.BUNDLE_STATE_CONFLICT)

        titleRequest(
            fixture.owner,
            fixture.bunchId,
            bundleId,
            screen = null,
            value = "발행본 제목 수정",
            expectedVersion = published.path("titleVersion").asLong(),
            leaseToken = null,
            mode = BundleStatus.PUBLISHED
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.title").value("발행본 제목 수정"))

        titleRequest(
            fixture.participant,
            fixture.bunchId,
            bundleId,
            screen = null,
            value = "참여자 제목 수정",
            expectedVersion = published.path("titleVersion").asLong(),
            leaseToken = null,
            mode = BundleStatus.PUBLISHED
        ).expectError(ErrorCode.FORBIDDEN)

        val updatedComments = commentRequest(
            fixture.participant,
            fixture.bunchId,
            bundleId,
            value = "수정된 참여자 코멘트",
            expectedVersion = publishedParticipantCommentVersion,
            mode = BundleStatus.PUBLISHED
        )
            .andExpect(status().isOk)
            .andReturn()
            .data()

        assertThat(updatedComments.path("comments").size()).isEqualTo(2)
        assertThat(commentContent(updatedComments, fixture.owner.memberId))
            .isEqualTo("생성자 코멘트")
        assertThat(commentContent(updatedComments, fixture.participant.memberId))
            .isEqualTo("수정된 참여자 코멘트")
    }

    private fun fixture(photoCount: Int, participantUploads: Boolean = true): Fixture {
        val owner = account()
        val participant = account()
        val bunch = create(owner.memberId)
        join(participant.memberId, issue(owner.memberId, bunch.id))
        val photoIds = (1..photoCount).map { ordinal ->
            readyPhoto(
                bunch.id,
                if (participantUploads) listOf(owner.memberId, participant.memberId) else listOf(owner.memberId),
                ordinal
            )
        }
        return Fixture(owner, participant, bunch.id, photoIds)
    }

    private fun readyPhoto(bunchId: Long, uploaderIds: List<Long>, ordinal: Int): Long {
        val bunch = bunchRepository.findById(bunchId).orElseThrow()
        val asset = imageAssets.saveAndFlush(
            ImageAsset(
                publicId = "bundle-http/${UUID.randomUUID()}",
                url = "https://images.example/bundle-${UUID.randomUUID()}.jpg"
            )
        )
        ownedAssetIds += asset.id
        val photo = photos.saveAndFlush(
            Photo(
                bunch = bunch,
                imageAsset = asset,
                fileHash = ordinal.toString(16).padStart(64, '0'),
                processingStatus = ProcessingStatus.READY,
                capturedDate = LocalDate.of(2024, 1, ordinal),
                capturedTime = LocalTime.NOON,
                dateSource = DateSource.USER
            )
        )
        uploaderIds.forEach { memberId ->
            photoUploaders.saveAndFlush(
                PhotoUploader(
                    photo = photo,
                    member = members.findById(memberId).orElseThrow(),
                    uploadBatchId = UUID.randomUUID()
                )
            )
        }
        return photo.id
    }

    private fun account(): Auth = sessionFor(member())

    private fun sessionFor(memberId: Long): Auth {
        val pair = tokens.generateToken(memberId, "ROLE_USER")
        val sessionId = tokens.getSessionId(pair.accessToken)
        sessions.save(
            sessionId,
            memberId,
            pair.refreshToken,
            tokens.getRefreshTokenValiditySeconds()
        )
        sessionIds += sessionId to memberId
        return Auth(memberId, pair.accessToken, sessionId)
    }

    private fun createDraft(
        auth: Auth,
        bunchId: Long,
        photoId: Long,
        idempotencyKey: String = UUID.randomUUID().toString()
    ): JsonNode = createRequest(
        auth,
        bunchId,
        createBody(listOf(photoId)),
        idempotencyKey
    ).andExpect(status().isOk).andReturn().data()

    private fun createRequest(
        auth: Auth,
        bunchId: Long,
        body: String,
        idempotencyKey: String?
    ): ResultActions {
        val request = post(bundlesPath(bunchId))
            .header(HttpHeaders.AUTHORIZATION, bearer(auth))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)
        if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey)
        return mvc.perform(request)
    }

    private fun titleRequest(
        auth: Auth,
        bunchId: Long,
        bundleId: Long,
        screen: String?,
        value: String,
        expectedVersion: Long,
        leaseToken: UUID?,
        mode: BundleStatus = BundleStatus.DRAFT
    ): ResultActions {
        val request = put("${bundlePath(bunchId, bundleId)}/title")
            .header(HttpHeaders.AUTHORIZATION, bearer(auth))
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(BundleTextRequest(value, expectedVersion, mode, leaseToken)))
        if (screen != null) request.header("X-Bundle-Screen", screen)
        return mvc.perform(request)
    }

    private fun visibilityRequest(
        auth: Auth,
        bunchId: Long,
        bundleId: Long,
        value: Visibility,
        expectedVersion: Long,
        mode: BundleStatus
    ): ResultActions = mvc.perform(
        put("${bundlePath(bunchId, bundleId)}/visibility")
            .header(HttpHeaders.AUTHORIZATION, bearer(auth))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                mapper.writeValueAsBytes(
                    mapOf(
                        "value" to value.name,
                        "expectedVersion" to expectedVersion,
                        "mode" to mode.name
                    )
                )
            )
    )

    private fun commentRequest(
        auth: Auth,
        bunchId: Long,
        bundleId: Long,
        value: String,
        expectedVersion: Long,
        mode: BundleStatus
    ): ResultActions = mvc.perform(
        put("${bundlePath(bunchId, bundleId)}/comments/me")
            .header(HttpHeaders.AUTHORIZATION, bearer(auth))
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(BundleTextRequest(value, expectedVersion, mode)))
    )

    private fun detail(auth: Auth, bunchId: Long, bundleId: Long): JsonNode = mvc.perform(
        get(bundlePath(bunchId, bundleId))
            .header(HttpHeaders.AUTHORIZATION, bearer(auth))
    ).andExpect(status().isOk).andReturn().data()

    private fun commentContent(data: JsonNode, memberId: Long): String =
        data.path("comments").first { it.path("memberId").asLong() == memberId }
            .path("content").asText()

    private fun createBody(photoIds: List<Long>): String =
        mapper.writeValueAsString(mapOf("photoIds" to photoIds))

    private fun bearer(auth: Auth): String = "Bearer ${auth.accessToken}"

    private fun bundlesPath(bunchId: Long): String = "/api/v1/bunches/$bunchId/bundles"

    private fun bundlePath(bunchId: Long, bundleId: Long): String =
        "${bundlesPath(bunchId)}/$bundleId"

    private fun summaryPath(bunchId: Long): String = "${bundlesPath(bunchId)}/summary"

    private fun ResultActions.expectError(error: ErrorCode): ResultActions =
        andExpect(status().`is`(error.status))
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value(error.code))
            .andExpect(jsonPath("$.error.message").value(error.messageKey))

    private fun MvcResult.data(): JsonNode = mapper.readTree(response.contentAsString).path("data")

    private data class Auth(
        val memberId: Long,
        val accessToken: String,
        val sessionId: String
    )

    private data class Fixture(
        val owner: Auth,
        val participant: Auth,
        val bunchId: Long,
        val photoIds: List<Long>
    )
}
