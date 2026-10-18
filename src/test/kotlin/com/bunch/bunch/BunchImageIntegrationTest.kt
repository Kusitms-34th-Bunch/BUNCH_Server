package com.bunch.bunch

import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bunch.service.BunchService
import com.bunch.global.exception.ErrorCode
import com.bunch.global.image.ProfileImageStorage
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataAccessException
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO

@SpringBootTest
@ActiveProfiles("test")
class BunchImageIntegrationTest : BunchIntegrationSupport() {
    @Autowired private lateinit var bunchRepository: BunchRepository
    @Autowired private lateinit var membershipRepository: BunchMembershipRepository
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    private lateinit var server: HttpServer
    private lateinit var imageBunches: BunchService
    private val uploaded = CopyOnWriteArrayList<String>()
    private val deleted = CopyOnWriteArrayList<String>()
    private var onUpload: () -> Unit = {}
    private var failDeletion = false
    private var failUpload = false

    @BeforeEach
    fun startImageStub() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1_1/test/image/upload") { exchange ->
            try {
                val body = exchange.requestBody.readAllBytes().toString(StandardCharsets.ISO_8859_1)
                val publicId = Regex("name=\"public_id\"[^\\r\\n]*\\r\\n(?:[^\\r\\n]+\\r\\n)*\\r\\n([^\\r\\n]+)")
                    .find(body)?.groupValues?.get(1)
                    ?: error("Missing public id in image upload")
                uploaded.add(publicId)
                onUpload()
                if (failUpload) reply(exchange, 503, "{}") else reply(exchange, 200,
                    """{"public_id":"$publicId","secure_url":"https://images.example/$publicId.jpg","resource_type":"image","format":"jpg","width":8,"height":8}""")
            } catch (_: Exception) {
                reply(exchange, 500, "{}")
            }
        }
        server.createContext("/v1_1/test/image/destroy") { exchange ->
            val fields = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8).split('&')
                .associate { part -> part.substringBefore('=') to URLDecoder.decode(part.substringAfter('='), StandardCharsets.UTF_8) }
            fields["public_id"]?.let { deleted.add(it) }
            reply(exchange, if (failDeletion) 503 else 200, """{"result":"ok"}""")
        }
        server.start()
        val storage = ProfileImageStorage("test", "test-key", "test-secret", "http://127.0.0.1:${server.address.port}")
        imageBunches = BunchService(bunchRepository, membershipRepository, members, storage, transactionManager)
    }

    @AfterEach
    fun stopImageStub() {
        if (::server.isInitialized) server.stop(0)
    }

    @Test
    fun `image replacement commits before deleting old image and reset preserves character`() {
        val owner = member()
        val bunch = create(owner)
        jdbc.update("UPDATE bunches SET image_public_id = 'old/image', image_url = 'https://images.example/old.jpg' WHERE id = ?", bunch.id)
        onUpload = {
            // This independent connection must acquire both locks while HTTP upload is in flight.
            TransactionTemplate(transactionManager).execute {
                jdbc.execute("SET LOCAL lock_timeout = '500ms'")
                jdbc.queryForObject("SELECT id FROM members WHERE id = ? FOR UPDATE", Long::class.java, owner)
                jdbc.queryForObject("SELECT id FROM bunches WHERE id = ? FOR UPDATE", Long::class.java, bunch.id)
            }
        }
        val result = imageBunches.updateProfileImage(owner, bunch.id, photo())
        assertThat(result.imageUrl).isEqualTo("https://images.example/${uploaded.single()}.jpg")
        assertThat(deleted).containsExactly("old/image")
        assertThat(bunchRepository.findById(bunch.id).orElse(null)?.imagePublicId).isEqualTo(uploaded.single())
        val reset = imageBunches.resetProfileImage(owner, bunch.id)
        assertThat(reset.imageUrl).isNull()
        assertThat(reset.characterType).isEqualTo(bunch.characterType)
        assertThat(deleted).containsExactly("old/image", uploaded.single())
        assertThat(bunchRepository.findById(bunch.id).orElse(null)?.imagePublicId).isNull()
    }

    @Test
    fun `image database update failure deletes new upload and preserves old image reference`() {
        val owner = member()
        val bunch = create(owner)
        jdbc.update("UPDATE bunches SET image_public_id = 'old/image', image_url = 'https://images.example/old.jpg' WHERE id = ?", bunch.id)
        rejectWrite("bunch_test_reject_image", "bunches", "UPDATE", "WHEN (NEW.image_public_id IS NOT NULL)")
        assertThatThrownBy { imageBunches.updateProfileImage(owner, bunch.id, photo()) }.isInstanceOf(DataAccessException::class.java)
        assertThat(uploaded).hasSize(1)
        assertThat(deleted).containsExactly(uploaded.single())
        assertThat(bunchRepository.findById(bunch.id).orElse(null)?.imagePublicId).isEqualTo("old/image")
        assertThat(bunchRepository.findById(bunch.id).orElse(null)?.imageUrl).isEqualTo("https://images.example/old.jpg")
    }

    @Test
    fun `permission changed during upload causes compensation after locked recheck`() {
        val owner = member()
        val bunch = create(owner)
        onUpload = { jdbc.update("UPDATE bunch_memberships SET status = 'LEFT' WHERE bunch_id = ? AND member_id = ?", bunch.id, owner) }
        assertError(ErrorCode.FORBIDDEN) { imageBunches.updateProfileImage(owner, bunch.id, photo()) }
        assertThat(uploaded).hasSize(1)
        assertThat(deleted).containsExactly(uploaded.single())
        assertThat(bunchRepository.findById(bunch.id).orElse(null)?.imageUrl).isNull()
    }

    @Test
    fun `member or pending account image mutation fails before upload`() {
        val owner = member()
        val participant = member()
        val pending = member(completed = false)
        val bunch = create(owner)
        join(participant, issue(owner, bunch.id))
        assertError(ErrorCode.FORBIDDEN) { imageBunches.updateProfileImage(participant, bunch.id, photo()) }
        assertError(ErrorCode.FORBIDDEN) { imageBunches.resetProfileImage(participant, bunch.id) }
        assertError(ErrorCode.SIGNUP_INCOMPLETE) { imageBunches.updateProfileImage(pending, bunch.id, photo()) }
        assertThat(uploaded).isEmpty()
        assertThat(deleted).isEmpty()
    }

    @Test
    fun `upload failure leaves database image unchanged and cleanup failure does not roll back success`() {
        val owner = member()
        val bunch = create(owner)
        jdbc.update("UPDATE bunches SET image_public_id = 'old/image', image_url = 'https://images.example/old.jpg' WHERE id = ?", bunch.id)
        failUpload = true
        assertError(ErrorCode.PROFILE_IMAGE_FAILED) { imageBunches.updateProfileImage(owner, bunch.id, photo()) }
        assertThat(bunchRepository.findById(bunch.id).orElse(null)?.imagePublicId).isEqualTo("old/image")
        failUpload = false
        failDeletion = true
        val result = imageBunches.updateProfileImage(owner, bunch.id, photo())
        assertThat(bunchRepository.findById(bunch.id).orElse(null)?.imageUrl).isEqualTo(result.imageUrl)
        assertThat(deleted).contains("old/image")
    }

    private fun photo(): MockMultipartFile {
        val bytes = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()
        return MockMultipartFile("photo", "photo.jpg", "image/jpeg", bytes)
    }

    private fun reply(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
