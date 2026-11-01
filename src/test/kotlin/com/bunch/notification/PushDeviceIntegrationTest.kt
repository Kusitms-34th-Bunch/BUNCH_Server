package com.bunch.notification

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.global.exception.ErrorCode
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import com.bunch.notification.domain.NotificationType
import com.bunch.notification.domain.PushDeviceRepository
import com.bunch.notification.domain.PushJob
import com.bunch.notification.domain.PushJobRepository
import com.bunch.notification.domain.PushJobStatus
import com.bunch.notification.dto.PushAlert
import com.bunch.notification.dto.PushAps
import com.bunch.notification.dto.PushPayload
import com.bunch.notification.service.DeviceTokenCipher
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@SpringBootTest(
    properties = [
        "notification.push.apns.sandbox-topic=com.bunch.integration.test",
        "notification.push.apns.production-topic=com.bunch.integration.test.production",
        "notification.push.device-encryption-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
    ]
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PushDeviceIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var tokens: TokenProvider

    @Autowired
    private lateinit var sessions: RefreshTokenRepository

    @Autowired
    private lateinit var devices: PushDeviceRepository

    @Autowired
    private lateinit var jobs: PushJobRepository

    @Autowired
    private lateinit var cipher: DeviceTokenCipher

    private val sessionIds = mutableListOf<Pair<String, Long>>()

    @BeforeEach
    fun cleanPushRowsBeforeTest() {
        jdbc.update("DELETE FROM push_jobs")
        jdbc.update("DELETE FROM push_devices")
        jdbc.update("DELETE FROM push_preferences")
    }

    @AfterEach
    fun cleanPushRowsAndSessionsAfterTest() {
        jdbc.update("DELETE FROM push_jobs")
        jdbc.update("DELETE FROM push_devices")
        jdbc.update("DELETE FROM push_preferences")
        sessionIds.forEach { (sessionId, memberId) -> sessions.delete(sessionId, memberId) }
        sessionIds.clear()
    }

    @Test
    fun `active session registers variable length hex tokens and persists encrypted JPA fields`() {
        val memberId = member()
        val auth = sessionFor(memberId)
        val firstInstallation = UUID.randomUUID()
        val secondInstallation = UUID.randomUUID()
        val firstToken = "A1".repeat(17)
        val secondToken = "b2".repeat(33)

        register(auth, firstInstallation, firstToken)
            .expectSuccessEnvelope()
            .andExpect(jsonPath("$.data.installationId").value(firstInstallation.toString()))
            .andExpect(jsonPath("$.data.environment").value("SANDBOX"))
            .andExpect(jsonPath("$.data.authorizationStatus").value("AUTHORIZED"))
        register(auth, secondInstallation, secondToken)
            .expectSuccessEnvelope()
            .andExpect(jsonPath("$.data.installationId").value(secondInstallation.toString()))

        val persisted = devices.findAllByMemberId(memberId).associateBy { it.installationId }
        assertThat(persisted).containsKeys(firstInstallation, secondInstallation)
        assertThat(cipher.decrypt(persisted.getValue(firstInstallation).tokenCiphertext))
            .isEqualTo(firstToken.lowercase())
        assertThat(cipher.decrypt(persisted.getValue(secondInstallation).tokenCiphertext))
            .isEqualTo(secondToken)
        assertThat(persisted.values.map { it.tokenHash })
            .allMatch { it.length == 64 && it.matches(Regex("[0-9a-f]{64}")) }

        assertThat(dbType("push_devices", "token_ciphertext")).isEqualTo("bytea")
        assertThat(dbType("push_devices", "token_hash")).isEqualTo("character")
        assertThat(dbType("push_preferences", "types")).isEqualTo("jsonb")
        assertThat(dbType("push_jobs", "payload")).isEqualTo("jsonb")
    }

    @Test
    fun `active session JWT is required to register a completed member and incomplete signup is rejected`() {
        val incompleteMemberId = member(completed = false)

        register(sessionFor(incompleteMemberId), UUID.randomUUID(), "c3".repeat(17))
            .expectError(ErrorCode.SIGNUP_INCOMPLETE)
    }

    @Test
    fun `foreign active session cannot delete another member device`() {
        val ownerId = member()
        val owner = sessionFor(ownerId)
        val installationId = UUID.randomUUID()
        register(owner, installationId, "d4".repeat(17)).expectSuccessEnvelope()
        val other = sessionFor(member())

        unregister(other, installationId)
            .expectError(ErrorCode.RESOURCE_NOT_FOUND)

        assertThat(devices.findAllByMemberId(ownerId)).hasSize(1)
    }

    @Test
    fun `HTTP logout removes only same sid devices cancels their jobs and repeat is unauthorized`() {
        val memberId = member()
        val firstSession = sessionFor(memberId)
        val secondSession = sessionFor(memberId)
        val firstInstallation = UUID.randomUUID()
        val secondInstallation = UUID.randomUUID()
        register(firstSession, firstInstallation, "d4".repeat(17)).expectSuccessEnvelope()
        register(secondSession, secondInstallation, "e5".repeat(17)).expectSuccessEnvelope()

        val devicesByInstallation = devices.findAllByMemberId(memberId).associateBy { it.installationId }
        val bunchId = create(memberId).id
        val createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val firstJobId = UUID.randomUUID()
        val secondJobId = UUID.randomUUID()
        jobs.saveAndFlush(
            testJob(
                firstJobId,
                devicesByInstallation.getValue(firstInstallation).id,
                memberId,
                UUID.fromString(firstSession.sessionId),
                bunchId,
                createdAt
            )
        )
        jobs.saveAndFlush(
            testJob(
                secondJobId,
                devicesByInstallation.getValue(secondInstallation).id,
                memberId,
                UUID.fromString(secondSession.sessionId),
                bunchId,
                createdAt
            )
        )

        logout(firstSession).expectEmptySuccess()

        assertThat(sessions.isActive(firstSession.sessionId, memberId)).isFalse()
        assertThat(sessions.isActive(secondSession.sessionId, memberId)).isTrue()
        assertThat(devices.findAllByMemberId(memberId).map { it.installationId })
            .containsExactly(secondInstallation)
        assertThat(jobs.findById(firstJobId).orElseThrow().status)
            .isEqualTo(PushJobStatus.CANCELLED)
        assertThat(jobs.findById(secondJobId).orElseThrow().status)
            .isEqualTo(PushJobStatus.PENDING)

        logout(firstSession).expectError(ErrorCode.UNAUTHORIZED)
        assertThat(devices.findAllByMemberId(memberId).map { it.installationId })
            .containsExactly(secondInstallation)
        assertThat(jobs.findById(secondJobId).orElseThrow().status)
            .isEqualTo(PushJobStatus.PENDING)
    }

    @Test
    fun `same installation and session registration is a no-op that preserves pending job`() {
        val memberId = member()
        val auth = sessionFor(memberId)
        val installationId = UUID.randomUUID()
        val token = "e5".repeat(17)
        register(auth, installationId, token).expectSuccessEnvelope()
        val device = devices.findAllByMemberId(memberId).single()
        val bunchId = create(memberId).id
        val jobId = UUID.randomUUID()
        val createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
        jobs.saveAndFlush(testJob(jobId, device.id, memberId, UUID.fromString(auth.sessionId), bunchId, createdAt))

        register(auth, installationId, token.uppercase()).expectSuccessEnvelope()

        val replayedDevice = devices.findAllByMemberId(memberId).single()
        val replayedJob = jobs.findById(jobId).orElseThrow()
        assertThat(replayedDevice.id).isEqualTo(device.id)
        assertThat(replayedJob.status).isEqualTo(PushJobStatus.PENDING)
        assertThat(replayedJob.deviceId).isEqualTo(device.id)
    }

    @Test
    fun `same token on a new installation binds the new session and cancels the old device job`() {
        val memberId = member()
        val oldAuth = sessionFor(memberId)
        val newAuth = sessionFor(memberId)
        val oldInstallationId = UUID.randomUUID()
        val newInstallationId = UUID.randomUUID()
        val token = "f6".repeat(33)
        register(oldAuth, oldInstallationId, token).expectSuccessEnvelope()
        val oldDevice = devices.findAllByMemberId(memberId).single()
        val bunchId = create(memberId).id
        val jobId = UUID.randomUUID()
        val createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
        jobs.saveAndFlush(
            testJob(jobId, oldDevice.id, memberId, UUID.fromString(oldAuth.sessionId), bunchId, createdAt)
        )

        register(newAuth, newInstallationId, token).expectSuccessEnvelope()

        val currentDevice = devices.findAllByMemberId(memberId).single()
        val oldJob = jobs.findById(jobId).orElseThrow()
        assertThat(currentDevice.installationId).isEqualTo(newInstallationId)
        assertThat(currentDevice.sessionId).isEqualTo(UUID.fromString(newAuth.sessionId))
        assertThat(currentDevice.sessionId).isNotEqualTo(UUID.fromString(oldAuth.sessionId))
        assertThat(oldJob.status).isEqualTo(PushJobStatus.CANCELLED)
    }

    @Test
    fun `concurrent preference patches merge different types and preserve existing enabled value`() {
        val memberId = member()
        val auth = sessionFor(memberId)
        patchPreferences(auth, """{"enabled":false,"types":{"MEMBER_JOINED":false}}""")
            .expectSuccessEnvelope()
            .andExpect(jsonPath("$.data.enabled").value(false))

        val results = concurrently(
            listOf(
                { patchPreferences(auth, """{"types":{"OWNER_TRANSFERRED":false}}""").andReturn() },
                { patchPreferences(auth, """{"types":{"BUNDLE_PUBLISHED":false}}""").andReturn() }
            )
        )
        results.forEach { result ->
            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrThrow().response.status).isEqualTo(200)
        }

        getPreferences(auth)
            .expectSuccessEnvelope()
            .andExpect(jsonPath("$.data.enabled").value(false))
            .andExpect(jsonPath("$.data.types.MEMBER_JOINED").value(false))
            .andExpect(jsonPath("$.data.types.OWNER_TRANSFERRED").value(false))
            .andExpect(jsonPath("$.data.types.BUNDLE_PUBLISHED").value(false))
    }

    @Test
    fun `preference patch rejects unknown notification type and non boolean value`() {
        val auth = sessionFor(member())

        patchPreferences(auth, """{"types":{"NOT_A_NOTIFICATION_TYPE":true}}""")
            .expectError(ErrorCode.INVALID_INPUT)
        patchPreferences(auth, """{"types":{"MEMBER_JOINED":"false"}}""")
            .expectError(ErrorCode.INVALID_JSON)
        patchPreferences(auth, """{"types":{"MEMBER_JOINED":0}}""")
            .expectError(ErrorCode.INVALID_JSON)
        patchPreferences(auth, """{"types":{"MEMBER_JOINED":null}}""")
            .expectError(ErrorCode.INVALID_JSON)
        patchPreferences(auth, """{"enabled":"false"}""")
            .expectError(ErrorCode.INVALID_JSON)
    }

    private fun register(auth: Auth, installationId: UUID, token: String): ResultActions = mvc.perform(
        put("/api/v1/members/me/push-devices/$installationId")
            .header(HttpHeaders.AUTHORIZATION, bearer(auth))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                mapper.writeValueAsString(
                    mapOf(
                        "deviceToken" to token,
                        "environment" to "SANDBOX",
                        "authorizationStatus" to "AUTHORIZED"
                    )
                )
            )
    )

    private fun unregister(auth: Auth, installationId: UUID): ResultActions = mvc.perform(
        delete("/api/v1/members/me/push-devices/$installationId")
            .header(HttpHeaders.AUTHORIZATION, bearer(auth))
    )

    private fun patchPreferences(auth: Auth, body: String): ResultActions = mvc.perform(
        patch("/api/v1/members/me/push-preferences")
            .header(HttpHeaders.AUTHORIZATION, bearer(auth))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)
    )

    private fun getPreferences(auth: Auth): ResultActions = mvc.perform(
        get("/api/v1/members/me/push-preferences")
            .header(HttpHeaders.AUTHORIZATION, bearer(auth))
    )

    private fun logout(auth: Auth): ResultActions = mvc.perform(
        post("/api/v1/auth/logout")
            .header(HttpHeaders.AUTHORIZATION, bearer(auth))
    )

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

    private fun testJob(
        id: UUID,
        deviceId: Long,
        memberId: Long,
        sessionId: UUID,
        bunchId: Long,
        createdAt: Instant
    ): PushJob = PushJob(
        id = id,
        deviceId = deviceId,
        recipientMemberId = memberId,
        sessionId = sessionId,
        bunchId = bunchId,
        notificationId = null,
        dedupeKey = "push-device-http-test:$id",
        type = NotificationType.MEMBER_JOINED,
        payload = PushPayload(
            aps = PushAps(PushAlert("테스트 제목", "테스트 본문")),
            eventId = id,
            type = NotificationType.MEMBER_JOINED,
            target = mapOf("kind" to "BUNCH_HOME", "bunchId" to bunchId)
        ),
        availableAt = createdAt,
        expiresAt = createdAt.plus(1, ChronoUnit.HOURS),
        createdAt = createdAt
    )

    private fun dbType(table: String, column: String): String = jdbc.queryForObject(
        """
        SELECT data_type
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = ?
          AND column_name = ?
        """.trimIndent(),
        String::class.java,
        table,
        column
    )!!

    private fun bearer(auth: Auth): String = "Bearer ${auth.accessToken}"

    private fun ResultActions.expectSuccessEnvelope(): ResultActions =
        andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").exists())
            .andExpect(jsonPath("$.error").isEmpty)

    private fun ResultActions.expectError(error: ErrorCode): ResultActions =
        andExpect(status().`is`(error.status))
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.data").isEmpty)
            .andExpect(jsonPath("$.error.code").value(error.code))
            .andExpect(jsonPath("$.error.message").value(error.messageKey))

    private fun ResultActions.expectEmptySuccess(): ResultActions =
        andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").isEmpty)
            .andExpect(jsonPath("$.error").isEmpty)

    private data class Auth(
        val memberId: Long,
        val accessToken: String,
        val sessionId: String
    )
}
