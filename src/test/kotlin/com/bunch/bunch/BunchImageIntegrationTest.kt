package com.bunch.bunch

import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bunch.service.BunchService
import com.bunch.global.exception.ErrorCode
import com.bunch.global.image.ImageUrls
import com.bunch.global.image.ProfileImageStorage
import com.bunch.global.image.R2TestServer
import com.bunch.global.profanity.ProfanityFilter
import com.bunch.image.domain.ImageAsset
import com.bunch.image.domain.ImageAssetRepository
import com.bunch.image.service.ImageAssetService
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
import java.util.UUID
import javax.imageio.ImageIO

@SpringBootTest
@ActiveProfiles("test")
class BunchImageIntegrationTest : BunchIntegrationSupport() {
    @Autowired private lateinit var bunchRepository: BunchRepository
    @Autowired private lateinit var membershipRepository: BunchMembershipRepository
    @Autowired private lateinit var assetRepository: ImageAssetRepository
    @Autowired private lateinit var profanity: ProfanityFilter
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    private lateinit var server: R2TestServer
    private lateinit var imageBunches: BunchService
    private val uploaded: List<String>
        get() = server.requests.filter { it.method == "PUT" && it.path.endsWith("/profile.jpg") }
            .map(::objectKey)
    private val deleted: List<String>
        get() = server.requests.filter { it.method == "DELETE" && it.path.endsWith("/profile.jpg") }
            .map(::objectKey)

    @BeforeEach
    fun startImageStub() {
        server = R2TestServer()
        val storage = ProfileImageStorage(server.storage)
        imageBunches = BunchService(
            ImageUrls(server.storage),
            bunchRepository,
            membershipRepository,
            members,
            storage,
            ImageAssetService(assetRepository, storage, transactionManager),
            profanity,
            transactionManager
        )
    }

    @AfterEach
    fun stopImageStub() {
        if (::server.isInitialized) server.close()
    }

    @Test
    fun `image replacement commits before deleting old image and reset preserves character`() {
        val owner = member()
        val bunch = create(owner)
        val oldImage = attachImage(bunch.id, "old", "https://images.example/old.jpg")
        server.onPut = {
            TransactionTemplate(transactionManager).execute {
                jdbc.execute("SET LOCAL lock_timeout = '500ms'")
                jdbc.queryForObject("SELECT id FROM members WHERE id = ? FOR UPDATE", Long::class.java, owner)
                jdbc.queryForObject("SELECT id FROM bunches WHERE id = ? FOR UPDATE", Long::class.java, bunch.id)
            }
        }
        val result = imageBunches.updateProfileImage(owner, bunch.id, photo())
        assertThat(result.imageUrl).contains("/bunch-test/${uploaded.single()}")
        assertThat(deleted).containsExactly(oldImage.objectKey)
        assertThat(bunchRepository.findById(bunch.id).orElseThrow().profileVersion).isEqualTo(1)
        assertThat(bunchRepository.findById(bunch.id).orElse(null)?.imageKey).isEqualTo(uploaded.single())
        val reset = imageBunches.resetProfileImage(owner, bunch.id)
        assertThat(bunchRepository.findById(bunch.id).orElseThrow().profileVersion).isEqualTo(2)
        imageBunches.resetProfileImage(owner, bunch.id)
        assertThat(bunchRepository.findById(bunch.id).orElseThrow().profileVersion).isEqualTo(2)
        assertThat(reset.imageUrl).isNull()
        assertThat(reset.characterType).isEqualTo(bunch.characterType)
        assertThat(deleted).containsExactly(oldImage.objectKey, uploaded.single())
        assertThat(bunchRepository.findById(bunch.id).orElse(null)?.imageKey).isNull()
    }

    @Test
    fun `image database update failure deletes new upload and preserves old image reference`() {
        val owner = member()
        val bunch = create(owner)
        val oldImage = attachImage(bunch.id, "old", "https://images.example/old.jpg")
        rejectWrite("bunch_test_reject_image", "bunches", "UPDATE", "WHEN (NEW.profile_image_asset_id IS NOT NULL)")
        assertThatThrownBy { imageBunches.updateProfileImage(owner, bunch.id, photo()) }.isInstanceOf(DataAccessException::class.java)
        assertThat(uploaded).hasSize(1)
        assertThat(deleted).containsExactly(uploaded.single())
        assertThat(bunchRepository.findById(bunch.id).orElse(null)?.imageKey).isEqualTo(oldImage.objectKey)
        assertThat(bunchRepository.findById(bunch.id).orElseThrow().profileVersion).isZero()
    }

    @Test
    fun `permission changed during upload causes compensation after locked recheck`() {
        val owner = member()
        val bunch = create(owner)
        server.onPut = { jdbc.update("UPDATE bunch_memberships SET status = 'LEFT' WHERE bunch_id = ? AND member_id = ?", bunch.id, owner) }
        assertError(ErrorCode.FORBIDDEN) { imageBunches.updateProfileImage(owner, bunch.id, photo()) }
        assertThat(uploaded).hasSize(1)
        assertThat(deleted).containsExactly(uploaded.single())
        assertThat(bunchRepository.findById(bunch.id).orElse(null)?.imageKey).isNull()
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
        val oldImage = attachImage(bunch.id, "old", "https://images.example/old.jpg")
        server.failPuts = true
        assertError(ErrorCode.PROFILE_IMAGE_FAILED) { imageBunches.updateProfileImage(owner, bunch.id, photo()) }
        assertThat(bunchRepository.findById(bunch.id).orElse(null)?.imageKey).isEqualTo(oldImage.objectKey)
        server.failPuts = false
        server.failDeletes = true
        val result = imageBunches.updateProfileImage(owner, bunch.id, photo())
        assertThat(result.imageUrl).contains("/bunch-test/${uploaded.last()}")
        assertThat(deleted).contains(oldImage.objectKey)
    }

    @Test
    fun `concurrent image resets increment the version and release the file only once`() {
        val owner = member()
        val bunch = create(owner)
        val previous = attachImage(bunch.id, "concurrent-reset", "https://images.example/reset.jpg")

        concurrently(List(4) { { imageBunches.resetProfileImage(owner, bunch.id) } })
            .forEach { it.getOrThrow() }

        val current = bunchRepository.findById(bunch.id).orElseThrow()
        assertThat(current.profileImageAsset).isNull()
        assertThat(current.profileVersion).isEqualTo(1)
        assertThat(deleted).containsExactly(previous.objectKey)
    }

    @Test
    fun `replacing or resetting bunch image preserves a shared personal profile asset`() {
        val owner = member()
        val bunch = create(owner)
        val shared = attachImage(bunch.id, "shared", "https://images.example/shared.jpg")
        jdbc.update("UPDATE members SET profile_image_asset_id = ? WHERE id = ?", shared.id, owner)

        imageBunches.updateProfileImage(owner, bunch.id, photo())
        assertThat(deleted).doesNotContain(shared.objectKey)
        assertThat(jdbc.queryForObject(
            "SELECT profile_image_asset_id FROM members WHERE id = ?",
            Long::class.java,
            owner
        )).isEqualTo(shared.id)
        assertThat(members.findById(owner).orElseThrow().imageKey).isEqualTo(shared.objectKey)

        imageBunches.resetProfileImage(owner, bunch.id)
        assertThat(deleted).doesNotContain(shared.objectKey)
        assertThat(assetRepository.existsById(shared.id)).isTrue()
        assertThat(members.findById(owner).orElseThrow().imageKey).isEqualTo(shared.objectKey)
    }

    private fun attachImage(bunchId: Long, prefix: String, url: String): ImageAsset {
        val image = assetRepository.saveAndFlush(
            ImageAsset(
                objectKey = "profiles/${UUID.randomUUID()}/profile.jpg"
            )
        )
        jdbc.update("UPDATE bunches SET profile_image_asset_id = ? WHERE id = ?", image.id, bunchId)
        return image
    }

    private fun photo(): MockMultipartFile {
        val bytes = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()
        return MockMultipartFile("photo", "photo.jpg", "image/jpeg", bytes)
    }

    private fun objectKey(request: R2TestServer.Request): String = request.path.substringAfter("/bunch-test/")
}
