package com.bunch.photo

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.image.PhotoImageStorage
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import com.bunch.photo.service.PhotoUploadLimits
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.`when`
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.multipart.MultipartFile
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.util.UUID
import javax.imageio.ImageIO

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PhotoUploadControllerIntegrationTest : PhotoUploadTestSupport() {
    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var tokens: TokenProvider

    @Autowired
    private lateinit var sessions: RefreshTokenRepository

    // The controller test runs the real inspection; only the external upload is replaced.
    private val inspector = PhotoImageStorage()
    private val sessionIds = mutableListOf<Pair<String, Long>>()

    @BeforeEach
    fun delegateInspection() {
        `when`(storage.inspect(anyFile())).thenAnswer { inspector.inspect(it.getArgument(0)) }
    }

    @AfterEach
    fun clearSessions() {
        sessionIds.forEach { (sessionId, memberId) -> sessions.delete(sessionId, memberId) }
        sessionIds.clear()
    }

    @Test
    fun `a member uploads a JPEG and receives the photo ID and READY photo`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val batchId = UUID.randomUUID()

        val response = upload(ownerId, bunchId, jpeg(1), batchId, "2026-10-03", "14:05:09")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.result").value("STORED"))
            .andReturn().response.contentAsString

        val photoId = jdbc.queryForObject("SELECT id FROM photos WHERE bunch_id = ?", Long::class.java, bunchId)!!
        assertThat(response).contains("\"photoId\":$photoId")
        val row = jdbc.queryForMap(
            "SELECT processing_status, captured_date::text AS d, captured_time::text AS t FROM photos WHERE id = ?",
            photoId
        )
        assertThat(row["processing_status"]).isEqualTo("READY")
        assertThat(row["d"]).isEqualTo("2026-10-03")
        assertThat(row["t"]).isEqualTo("14:05:09")
        val uploader = jdbc.queryForMap("SELECT upload_batch_id FROM photo_uploaders WHERE photo_id = ?", photoId)
        assertThat(uploader["upload_batch_id"].toString()).isEqualTo(batchId.toString())
    }

    @Test
    fun `the same file again is excluded and another member's upload is merged`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherId = joined(ownerId, bunchId)
        val file = jpeg(2)

        upload(ownerId, bunchId, file).andExpect(jsonPath("$.data.result").value("STORED"))
        upload(ownerId, bunchId, file).andExpect(jsonPath("$.data.result").value("ALREADY_UPLOADED"))
        upload(otherId, bunchId, file).andExpect(jsonPath("$.data.result").value("MERGED"))

        assertThat(photoCount(bunchId)).isEqualTo(1)
        verify(storage, times(1)).upload(anyImage())
    }

    @Test
    fun `a photo without capture info is accepted`() {
        val (ownerId, bunchId) = bunchWithOwner()

        upload(ownerId, bunchId, jpeg(3)).andExpect(status().isOk)

        val source = jdbc.queryForObject(
            "SELECT date_source FROM photos WHERE bunch_id = ?",
            String::class.java,
            bunchId
        )
        assertThat(source).isEqualTo("UNKNOWN")
    }

    @Test
    fun `a request without a token is unauthorized`() {
        val (_, bunchId) = bunchWithOwner()

        mvc.perform(
            multipart(path(bunchId))
                .file(photoPart(jpeg(4)))
                .param("uploadBatchId", UUID.randomUUID().toString())
        ).andExpect(status().isUnauthorized)

        assertThat(photoCount(bunchId)).isZero()
    }

    @Test
    fun `non members and unknown bunches are rejected`() {
        val (_, bunchId) = bunchWithOwner()
        val outsiderId = member()

        upload(outsiderId, bunchId, jpeg(5)).expectError(ErrorCode.FORBIDDEN)
        upload(outsiderId, Long.MAX_VALUE, jpeg(6)).expectError(ErrorCode.RESOURCE_NOT_FOUND)

        assertThat(photoCount(bunchId)).isZero()
        verify(storage, never()).upload(anyImage())
    }

    @Test
    fun `files that are not accepted JPEGs are rejected as invalid input`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val png = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()

        upload(ownerId, bunchId, png, contentType = "image/png").expectError(ErrorCode.INVALID_INPUT)
        upload(ownerId, bunchId, "not an image".toByteArray()).expectError(ErrorCode.INVALID_INPUT)
        upload(ownerId, bunchId, withLocation(jpeg(7))).expectError(ErrorCode.INVALID_INPUT)

        assertThat(photoCount(bunchId)).isZero()
        verify(storage, never()).upload(anyImage())
    }

    @Test
    fun `missing or malformed request fields are rejected as invalid input`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val token = bearer(ownerId)
        val file = photoPart(jpeg(8))

        mvc.perform(multipart(path(bunchId)).param("uploadBatchId", UUID.randomUUID().toString())
            .header(HttpHeaders.AUTHORIZATION, token)).expectError(ErrorCode.INVALID_INPUT)
        mvc.perform(multipart(path(bunchId)).file(file).header(HttpHeaders.AUTHORIZATION, token))
            .expectError(ErrorCode.INVALID_INPUT)
        mvc.perform(multipart(path(bunchId)).file(file).param("uploadBatchId", "not-a-uuid")
            .header(HttpHeaders.AUTHORIZATION, token)).expectError(ErrorCode.INVALID_INPUT)
        upload(ownerId, bunchId, jpeg(9), capturedDate = "03/10/2026").expectError(ErrorCode.INVALID_INPUT)
        upload(ownerId, bunchId, jpeg(10), capturedTime = "14:05:09").expectError(ErrorCode.INVALID_INPUT)

        assertThat(photoCount(bunchId)).isZero()
        verify(storage, never()).upload(anyImage())
    }

    @Test
    fun `the UTC offset and the original type are stored, and the app's SAVED_IMAGE is kept as SAVED`() {
        val (ownerId, bunchId) = bunchWithOwner()

        upload(ownerId, bunchId, jpeg(13), capturedUtcOffset = "+09:00", sourceType = "CAMERA")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.result").value("STORED"))
        upload(ownerId, bunchId, jpeg(14), capturedUtcOffset = "-12:00", sourceType = "SAVED_IMAGE")
            .andExpect(jsonPath("$.data.result").value("STORED"))
        upload(ownerId, bunchId, jpeg(15), capturedUtcOffset = "+14:00", sourceType = "SCREENSHOT")
            .andExpect(jsonPath("$.data.result").value("STORED"))

        assertThat(originsOf(bunchId)).containsExactlyInAnyOrder(
            "+09:00" to "CAMERA",
            "-12:00" to "SAVED",
            "+14:00" to "SCREENSHOT"
        )
    }

    @Test
    fun `an unknown or empty original type and an empty offset count as not sent`() {
        val (ownerId, bunchId) = bunchWithOwner()

        upload(ownerId, bunchId, jpeg(16), sourceType = "UNKNOWN").andExpect(status().isOk)
        upload(ownerId, bunchId, jpeg(17), capturedUtcOffset = "", sourceType = "").andExpect(status().isOk)
        upload(ownerId, bunchId, jpeg(18)).andExpect(status().isOk)

        assertThat(originsOf(bunchId)).containsExactly(null to null, null to null, null to null)
    }

    @Test
    fun `a wrong offset or original type is a plain 400 and nothing is stored or uploaded`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val wrongOffsets = listOf(
            "+9:00", "+0900", "09:00", "+09:60", "+14:01", "-12:01", "+15:00", "-13:00", "Z", "+09:00 "
        )
        val wrongTypes = listOf("camera", "Camera", "SAVED", "PNG", "CAMERA ", "0")

        wrongOffsets.forEachIndexed { index, offset ->
            upload(ownerId, bunchId, jpeg(20 + index), capturedUtcOffset = offset)
                .expectError(ErrorCode.INVALID_INPUT)
                .andExpect(jsonPath("$.error.detail").doesNotExist())
        }
        wrongTypes.forEachIndexed { index, type ->
            upload(ownerId, bunchId, jpeg(40 + index), sourceType = type)
                .expectError(ErrorCode.INVALID_INPUT)
                .andExpect(jsonPath("$.error.detail").doesNotExist())
        }

        assertThat(photoCount(bunchId)).isZero()
        verify(storage, never()).upload(anyImage())
    }

    @Test
    fun `an upload of a known photo with the new fields fills what was missing and answers as before`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherId = joined(ownerId, bunchId)
        val file = jpeg(60)

        upload(ownerId, bunchId, file).andExpect(jsonPath("$.data.result").value("STORED"))
        upload(otherId, bunchId, file, capturedUtcOffset = "+01:00", sourceType = "SAVED_IMAGE")
            .andExpect(jsonPath("$.data.result").value("MERGED"))
        upload(ownerId, bunchId, file, capturedUtcOffset = "+09:00", sourceType = "CAMERA")
            .andExpect(jsonPath("$.data.result").value("ALREADY_UPLOADED"))

        assertThat(originsOf(bunchId)).containsExactly("+01:00" to "SAVED")
    }

    @Test
    fun `a full bunch answers with the bunch limit error`() {
        val (ownerId, bunchId) = bunchWithOwner()
        seedPhotos(bunchId, PhotoUploadLimits.MAX_PHOTOS_PER_BUNCH)

        upload(ownerId, bunchId, jpeg(11)).expectError(ErrorCode.PHOTO_BUNCH_LIMIT_EXCEEDED)
    }

    @Test
    fun `a storage outage is reported as a retryable service error`() {
        val (ownerId, bunchId) = bunchWithOwner()
        `when`(storage.upload(anyImage())).thenThrow(BusinessException(ErrorCode.PHOTO_IMAGE_STORAGE_FAILED))

        upload(ownerId, bunchId, jpeg(12)).expectError(ErrorCode.PHOTO_IMAGE_STORAGE_FAILED)

        assertThat(photoCount(bunchId)).isZero()
    }

    private fun upload(
        memberId: Long,
        bunchId: Long,
        bytes: ByteArray,
        batchId: UUID = UUID.randomUUID(),
        capturedDate: String? = null,
        capturedTime: String? = null,
        contentType: String = "image/jpeg",
        capturedUtcOffset: String? = null,
        sourceType: String? = null
    ): ResultActions {
        val request = multipart(path(bunchId))
            .file(photoPart(bytes, contentType))
            .param("uploadBatchId", batchId.toString())
            .header(HttpHeaders.AUTHORIZATION, bearer(memberId))
        if (capturedDate != null) request.param("capturedDate", capturedDate)
        if (capturedTime != null) request.param("capturedTime", capturedTime)
        if (capturedUtcOffset != null) request.param("capturedUtcOffset", capturedUtcOffset)
        if (sourceType != null) request.param("sourceType", sourceType)
        return mvc.perform(request)
    }

    // The offset and the original type of every photo of the bunch, oldest first.
    private fun originsOf(bunchId: Long): List<Pair<String?, String?>> = jdbc.query(
        """SELECT p.captured_utc_offset AS utc_offset, f.source AS source
            FROM photos p LEFT JOIN photo_reco_feature f ON f.photo_id = p.id
            WHERE p.bunch_id = ? ORDER BY p.id""",
        { rs, _ -> rs.getString("utc_offset") to rs.getString("source") },
        bunchId
    )

    private fun photoPart(bytes: ByteArray, contentType: String = "image/jpeg") =
        MockMultipartFile("photo", "from-the-app.jpg", contentType, bytes)

    private fun path(bunchId: Long) = "/api/v1/bunches/$bunchId/photos"

    private fun bearer(memberId: Long): String {
        val pair = tokens.generateToken(memberId, "ROLE_USER")
        val sessionId = tokens.getSessionId(pair.accessToken)
        sessions.save(sessionId, memberId, pair.refreshToken, tokens.getRefreshTokenValiditySeconds())
        sessionIds += sessionId to memberId
        return "Bearer ${pair.accessToken}"
    }

    private fun ResultActions.expectError(error: ErrorCode): ResultActions =
        andExpect(status().`is`(error.status))
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value(error.code))
            .andExpect(jsonPath("$.error.message").value(error.messageKey))

    private fun anyFile(): MultipartFile =
        ArgumentMatchers.any(MultipartFile::class.java) ?: MockMultipartFile("x", ByteArray(0))

    // A different color per seed gives a different file, so each test controls which uploads are the same.
    private fun jpeg(seed: Int): ByteArray {
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color(seed * 20 % 256, seed * 37 % 256, seed * 53 % 256)
        graphics.fillRect(0, 0, 64, 64)
        graphics.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpeg", it) }.toByteArray()
    }

    // An EXIF block whose first IFD holds the GPS pointer (tag 0x8825), inserted after the SOI marker.
    private fun withLocation(jpeg: ByteArray): ByteArray {
        val tiff = "Exif".toByteArray(ISO_8859_1) + byteArrayOf(0, 0) + "II".toByteArray() +
            byteArrayOf(42, 0, 8, 0, 0, 0, 1, 0, 0x25, 0x88.toByte(), 4, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val length = tiff.size + 2
        val segment = byteArrayOf(0xff.toByte(), 0xe1.toByte(), (length shr 8).toByte(), length.toByte()) + tiff
        return jpeg.copyOfRange(0, 2) + segment + jpeg.copyOfRange(2, jpeg.size)
    }
}
