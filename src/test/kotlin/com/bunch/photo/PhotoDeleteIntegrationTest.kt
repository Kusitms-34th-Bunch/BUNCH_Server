package com.bunch.photo

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bundle.domain.BundlePhotoRepository
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.dto.BundleActor
import com.bunch.bundle.dto.CreateBundleRequest
import com.bunch.bundle.service.BundleService
import com.bunch.global.image.ProfileImageStorage
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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PhotoDeleteIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var tokens: TokenProvider

    @Autowired
    private lateinit var sessions: RefreshTokenRepository

    @Autowired
    private lateinit var bunchRepository: BunchRepository

    @Autowired
    private lateinit var assets: ImageAssetRepository

    @Autowired
    private lateinit var photos: PhotoRepository

    @Autowired
    private lateinit var uploaders: PhotoUploaderRepository

    @Autowired
    private lateinit var bundleService: BundleService

    @Autowired
    private lateinit var bundleRepository: BundleRepository

    @Autowired
    private lateinit var bundlePhotos: BundlePhotoRepository

    @MockitoBean
    private lateinit var storage: ProfileImageStorage

    private val sessionIds = mutableListOf<Pair<String, Long>>()
    private val ownedAssetIds = mutableListOf<Long>()

    @AfterEach
    fun cleanPhotoDeleteData() {
        // BunchIntegrationSupport removes bunches and members afterwards, so clear what references them first.
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
    fun `a member deletes a photo only they uploaded`() {
        val fixture = fixture()
        val mine = photo(fixture.bunchId, listOf(fixture.guest))
        val assetPublicId = mine.imageAsset.publicId

        delete(fixture.guest, fixture.bunchId, listOf(mine.id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.deletedCount").value(1))
            .andExpect(jsonPath("$.data.uploaderRemovedCount").value(0))

        assertThat(photos.existsById(mine.id)).isFalse()
        assertThat(assets.existsById(mine.imageAsset.id)).isFalse()
        verify(storage, times(1)).delete(assetPublicId)
    }

    @Test
    fun `deleting a jointly uploaded photo removes only my badge`() {
        val fixture = fixture()
        val joint = photo(fixture.bunchId, listOf(fixture.owner, fixture.guest))

        delete(fixture.guest, fixture.bunchId, listOf(joint.id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.deletedCount").value(0))
            .andExpect(jsonPath("$.data.uploaderRemovedCount").value(1))

        assertThat(photos.existsById(joint.id)).isTrue()
        assertThat(uploaderIds(joint.id)).containsExactly(fixture.owner)
        verifyNoInteractions(storage)
    }

    @Test
    fun `the last remaining uploader of a jointly uploaded photo deletes the photo`() {
        val fixture = fixture()
        val joint = photo(fixture.bunchId, listOf(fixture.owner, fixture.guest))

        delete(fixture.guest, fixture.bunchId, listOf(joint.id)).andExpect(status().isOk)
        delete(fixture.owner, fixture.bunchId, listOf(joint.id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.deletedCount").value(1))
            .andExpect(jsonPath("$.data.uploaderRemovedCount").value(0))

        assertThat(photos.existsById(joint.id)).isFalse()
    }

    @Test
    fun `an administrator deletes a photo uploaded only by others whole`() {
        val fixture = fixture()
        val others = photo(fixture.bunchId, listOf(fixture.guest, fixture.third))

        delete(fixture.owner, fixture.bunchId, listOf(others.id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.deletedCount").value(1))
            .andExpect(jsonPath("$.data.uploaderRemovedCount").value(0))

        assertThat(photos.existsById(others.id)).isFalse()
        assertThat(uploaderIds(others.id)).isEmpty()
        assertThat(assets.existsById(others.imageAsset.id)).isFalse()
        verify(storage, times(1)).delete(others.imageAsset.publicId)
    }

    @Test
    fun `an administrator who also uploaded a joint photo removes only their own badge`() {
        val fixture = fixture()
        val joint = photo(fixture.bunchId, listOf(fixture.owner, fixture.guest))

        delete(fixture.owner, fixture.bunchId, listOf(joint.id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.deletedCount").value(0))
            .andExpect(jsonPath("$.data.uploaderRemovedCount").value(1))

        assertThat(uploaderIds(joint.id)).containsExactly(fixture.guest)
    }

    @Test
    fun `a mixed selection removes sole photos and only the badge of joint photos`() {
        val fixture = fixture()
        val sole = photo(fixture.bunchId, listOf(fixture.guest))
        val joint = photo(fixture.bunchId, listOf(fixture.guest, fixture.third))
        val soleToo = photo(fixture.bunchId, listOf(fixture.guest))

        delete(fixture.guest, fixture.bunchId, listOf(sole.id, joint.id, soleToo.id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.deletedCount").value(2))
            .andExpect(jsonPath("$.data.uploaderRemovedCount").value(1))

        assertThat(photos.existsById(sole.id)).isFalse()
        assertThat(photos.existsById(soleToo.id)).isFalse()
        assertThat(photos.existsById(joint.id)).isTrue()
        assertThat(uploaderIds(joint.id)).containsExactly(fixture.third)
    }

    @Test
    fun `a member cannot delete photos uploaded only by others and nothing is deleted`() {
        val fixture = fixture()
        val mine = photo(fixture.bunchId, listOf(fixture.guest))
        val foreignUpload = photo(fixture.bunchId, listOf(fixture.owner))

        delete(fixture.guest, fixture.bunchId, listOf(mine.id, foreignUpload.id))
            .andExpect(status().isForbidden)

        assertThat(photos.existsById(mine.id)).isTrue()
        assertThat(photos.existsById(foreignUpload.id)).isTrue()
        assertThat(uploaderIds(mine.id)).containsExactly(fixture.guest)
        verifyNoInteractions(storage)
    }

    @Test
    fun `a draft bundle loses the deleted photo and an emptied draft is removed`() {
        val fixture = fixture()
        val kept = photo(fixture.bunchId, listOf(fixture.owner))
        val removed = photo(fixture.bunchId, listOf(fixture.owner))
        val twoPhotoDraft = bundleService.create(
            BundleActor(fixture.owner, "session-${fixture.owner}"),
            fixture.bunchId,
            UUID.randomUUID(),
            CreateBundleRequest(listOf(kept.id, removed.id))
        )
        val onePhotoSource = photo(fixture.bunchId, listOf(fixture.owner))
        val onePhotoDraft = bundleService.create(
            BundleActor(fixture.owner, "session-${fixture.owner}"),
            fixture.bunchId,
            UUID.randomUUID(),
            CreateBundleRequest(listOf(onePhotoSource.id))
        )

        delete(fixture.owner, fixture.bunchId, listOf(removed.id, onePhotoSource.id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.deletedCount").value(2))

        assertThat(bundlePhotos.findAllByBundleIdOrderByPositionAscIdAsc(twoPhotoDraft.id).map { it.photoId })
            .containsExactly(kept.id)
        assertThat(bundleRepository.existsById(twoPhotoDraft.id)).isTrue()
        assertThat(bundleRepository.existsById(onePhotoDraft.id)).isFalse()
    }

    @Test
    fun `invalid selections are rejected before anything is deleted`() {
        val fixture = fixture()
        val mine = photo(fixture.bunchId, listOf(fixture.guest))
        val tooMany = (1..51L).toList()

        listOf(
            emptyList(),
            listOf(mine.id, mine.id),
            listOf(mine.id, 0L),
            listOf(mine.id, -4L),
            tooMany
        ).forEach {
            delete(fixture.guest, fixture.bunchId, it).andExpect(status().isBadRequest)
        }
        post("/api/v1/bunches/${fixture.bunchId}/photos/delete")
            .header(HttpHeaders.AUTHORIZATION, bearer(fixture.guest))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"photoIds":"not-a-list"}""")
            .let { mvc.perform(it) }
            .andExpect(status().isBadRequest)

        assertThat(photos.existsById(mine.id)).isTrue()
    }

    @Test
    fun `a photo of another bunch or one that is not ready rejects the whole request`() {
        val fixture = fixture()
        val mine = photo(fixture.bunchId, listOf(fixture.guest))
        val otherBunchId = create(fixture.owner).id
        val foreign = photo(otherBunchId, listOf(fixture.owner))
        val pending = photo(fixture.bunchId, listOf(fixture.guest), ProcessingStatus.PENDING)

        delete(fixture.guest, fixture.bunchId, listOf(mine.id, foreign.id)).andExpect(status().isBadRequest)
        delete(fixture.guest, fixture.bunchId, listOf(mine.id, pending.id)).andExpect(status().isBadRequest)

        assertThat(photos.existsById(mine.id)).isTrue()
        assertThat(photos.existsById(foreign.id)).isTrue()
        assertThat(photos.existsById(pending.id)).isTrue()
    }

    @Test
    fun `photos that are already gone are skipped so a retry is safe`() {
        val fixture = fixture()
        val mine = photo(fixture.bunchId, listOf(fixture.guest))

        delete(fixture.guest, fixture.bunchId, listOf(mine.id, Long.MAX_VALUE))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.deletedCount").value(1))
        delete(fixture.guest, fixture.bunchId, listOf(mine.id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.deletedCount").value(0))
            .andExpect(jsonPath("$.data.uploaderRemovedCount").value(0))

        verify(storage, times(1)).delete(mine.imageAsset.publicId)
    }

    @Test
    fun `only active members of the bunch can delete`() {
        val fixture = fixture()
        val mine = photo(fixture.bunchId, listOf(fixture.guest))
        val outsider = member()
        val incomplete = member(completed = false)

        delete(outsider, fixture.bunchId, listOf(mine.id)).andExpect(status().isForbidden)
        delete(incomplete, fixture.bunchId, listOf(mine.id)).andExpect(status().isForbidden)
        delete(fixture.guest, Long.MAX_VALUE, listOf(mine.id)).andExpect(status().isNotFound)
        mvc.perform(
            post("/api/v1/bunches/${fixture.bunchId}/photos/delete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"photoIds":[${mine.id}]}""")
        ).andExpect(status().isUnauthorized)

        assertThat(photos.existsById(mine.id)).isTrue()
    }

    @Test
    fun `a member who left the bunch can no longer delete`() {
        val fixture = fixture()
        val mine = photo(fixture.bunchId, listOf(fixture.guest))
        jdbc.update(
            "UPDATE bunch_memberships SET status = 'LEFT' WHERE bunch_id = ? AND member_id = ?",
            fixture.bunchId,
            fixture.guest
        )

        delete(fixture.guest, fixture.bunchId, listOf(mine.id)).andExpect(status().isForbidden)

        assertThat(photos.existsById(mine.id)).isTrue()
    }

    @Test
    fun `simultaneous deletes of one photo remove it and its asset once`() {
        val fixture = fixture()
        val joint = photo(fixture.bunchId, listOf(fixture.owner, fixture.guest))
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val calls = listOf(fixture.owner, fixture.guest).map { who ->
                val authorization = bearer(who)
                pool.submit(Callable {
                    start.await()
                    mvc.perform(
                        post("/api/v1/bunches/${fixture.bunchId}/photos/delete")
                            .header(HttpHeaders.AUTHORIZATION, authorization)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"photoIds":[${joint.id}]}""")
                    ).andReturn().response.status
                })
            }
            start.countDown()
            assertThat(calls.map { it.get(30, TimeUnit.SECONDS) }).containsOnly(200)
        } finally {
            pool.shutdownNow()
        }

        assertThat(photos.existsById(joint.id)).isFalse()
        verify(storage, times(1)).delete(joint.imageAsset.publicId)
    }

    private fun delete(memberId: Long, bunchId: Long, photoIds: List<Long>): ResultActions =
        mvc.perform(
            post("/api/v1/bunches/$bunchId/photos/delete")
                .header(HttpHeaders.AUTHORIZATION, bearer(memberId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"photoIds":${photoIds.joinToString(prefix = "[", postfix = "]")}}""")
        )

    private fun bearer(memberId: Long): String {
        val pair = tokens.generateToken(memberId, "ROLE_USER")
        val sessionId = tokens.getSessionId(pair.accessToken)
        sessions.save(sessionId, memberId, pair.refreshToken, tokens.getRefreshTokenValiditySeconds())
        sessionIds += sessionId to memberId
        return "Bearer ${pair.accessToken}"
    }

    private data class Fixture(val bunchId: Long, val owner: Long, val guest: Long, val third: Long)

    /** A bunch whose administrator is [Fixture.owner] and whose other two members are ordinary members. */
    private fun fixture(): Fixture {
        val owner = member()
        val guest = member()
        val third = member()
        val bunchId = create(owner).id
        join(guest, issue(owner, bunchId))
        join(third, issue(owner, bunchId))
        return Fixture(bunchId, owner, guest, third)
    }

    private fun uploaderIds(photoId: Long): List<Long> =
        jdbc.queryForList(
            "SELECT member_id FROM photo_uploaders WHERE photo_id = ? ORDER BY id",
            Long::class.java,
            photoId
        )

    private fun photo(
        bunchId: Long,
        uploadedBy: List<Long>,
        status: ProcessingStatus = ProcessingStatus.READY
    ): Photo {
        val asset = assets.saveAndFlush(
            ImageAsset(
                publicId = "photo-delete/${UUID.randomUUID()}",
                url = "https://images.example/photo-delete/${UUID.randomUUID()}.jpg"
            )
        )
        ownedAssetIds += asset.id
        val photo = photos.saveAndFlush(
            Photo(
                bunch = bunchRepository.findById(bunchId).orElseThrow(),
                imageAsset = asset,
                fileHash = UUID.randomUUID().toString().replace("-", "").repeat(2),
                processingStatus = status,
                capturedDate = LocalDate.of(2026, 10, 19),
                capturedTime = LocalTime.NOON,
                dateSource = DateSource.EXIF
            )
        )
        val batch = UUID.randomUUID()
        uploadedBy.forEach {
            uploaders.saveAndFlush(
                PhotoUploader(
                    photo = photo,
                    member = members.findById(it).orElseThrow(),
                    uploadBatchId = batch
                )
            )
        }
        return photo
    }
}
