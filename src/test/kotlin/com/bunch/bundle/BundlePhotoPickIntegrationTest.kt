package com.bunch.bundle

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bundle.domain.BundlePhotoPickRepository
import com.bunch.bundle.service.BundlePhotoPickService
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.bundle.dto.*
import com.bunch.bundle.service.BundleService
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import com.bunch.image.domain.ImageAsset
import com.bunch.image.domain.ImageAssetRepository
import com.bunch.photo.domain.Photo
import com.bunch.photo.domain.PhotoRepository
import com.bunch.photo.domain.ProcessingStatus
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BundlePhotoPickIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var service: BundleService

    @Autowired
    private lateinit var pickService: BundlePhotoPickService

    @Autowired
    private lateinit var pickRows: BundlePhotoPickRepository

    @Autowired
    private lateinit var transactions: PlatformTransactionManager

    @Autowired
    private lateinit var bunchRows: BunchRepository

    @Autowired
    private lateinit var assets: ImageAssetRepository

    @Autowired
    private lateinit var photos: PhotoRepository

    @Autowired
    private lateinit var tokens: TokenProvider

    @Autowired
    private lateinit var sessions: RefreshTokenRepository

    private val credentials = mutableListOf<Pair<Long, String>>()
    private val assetIds = mutableListOf<Long>()

    @AfterEach
    fun clean() {
        jdbc.update("DELETE FROM bundles")
        jdbc.update("DELETE FROM photo_uploaders")
        jdbc.update("DELETE FROM photos")
        assetIds.forEach { jdbc.update("DELETE FROM image_assets WHERE id = ?", it) }
        credentials.forEach { (id, sid) -> sessions.delete(sid, id) }
    }

    @Test
    fun `desired state is idempotent and late retries cannot undo final cancellation`() {
        val f = fixture()
        val initial = detail(f).path("photos").first().path("picks")
        assertThat(initial.path("count").asInt()).isZero()
        val body = """{"picked":true,"expectedVersion":${initial.path("version").asLong()}}"""
        val picked = write(f, body)
        assertThat(picked.path("pickedByMe").asBoolean()).isTrue()
        assertThat(picked.path("count").asInt()).isEqualTo(1)
        assertThat(picked.path("members").first().path("memberId").asLong()).isEqualTo(f.owner)
        assertThat(picked.path("members").first().path("profileAvailable").asBoolean()).isTrue()
        assertThat(write(f, body)).isEqualTo(picked)
        val cleared = write(f, """{"picked":false,"expectedVersion":${picked.path("version").asLong()}}""")
        assertThat(cleared.path("count").asInt()).isZero()
        val rejected = write(f, body, 409)
        assertThat(rejected.path("code").asText()).isEqualTo("BUNDLE_003")
        assertThat(rejected.path("detail").path("version").asLong()).isEqualTo(cleared.path("version").asLong())
        assertThat(detail(f).path("photos").first().path("picks").path("pickedByMe").asBoolean()).isFalse()
        val missing = mvc.perform(put(path(f)).header("Authorization", "Bearer ${token(f.owner)}")
            .contentType(MediaType.APPLICATION_JSON).content("""{"expectedVersion":0}"""))
            .andReturn().response.status
        assertThat(missing).isEqualTo(400)
        listOf(
            """{"picked":null,"expectedVersion":0}""",
            """{"picked":true}""",
            """{"picked":true,"expectedVersion":null}"""
        ).forEach { write(f, it, 400) }
        mvc.perform(put(path(f)).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized())
    }

    @Test
    fun `nonparticipants read picks but cannot write and foreign photos are rejected`() {
        val f = fixture()
        assertThat(detail(f, f.guest).path("photos").first().path("picks").path("count").asInt()).isZero()
        write(f, """{"picked":true,"expectedVersion":0}""", 403, f.guest)
        val other = service.create(BundleActor(f.owner, "test"), f.bunchId, UUID.randomUUID(),
            CreateBundleRequest(listOf(f.sourceId)))
        write(f.copy(photoId = other.photos.single().id), """{"picked":true,"expectedVersion":0}""", 404)
        write(f, """{"picked":true,"expectedVersion":-1}""", 400)
    }

    @Test
    fun `concurrent duplicate picks have one effect and stale independent requests cannot undo newer state`() {
        val f = fixture()
        val version = service.get(f.owner, f.bunchId, f.id).revision
        val beforeEvents = count("notifications")
        val results = concurrently(List(4) {
            { pickService.save(f.owner, f.bunchId, f.id, f.photoId, BundlePhotoPickRequest(true, version)) }
        })
        assertThat(results.all { it.isSuccess }).isTrue()
        assertThat(results.map { it.getOrThrow().version }.distinct()).hasSize(1)
        val selected = results.first().getOrThrow()
        assertThat(count("bundle_photo_picks")).isEqualTo(1)
        assertThat(service.get(f.owner, f.bunchId, f.id).revision).isEqualTo(version + 1)
        assertThat(count("notifications")).isEqualTo(beforeEvents)
        pickService.save(f.owner, f.bunchId, f.id, f.photoId, BundlePhotoPickRequest(false, selected.version))
        assertError(ErrorCode.BUNDLE_VERSION_CONFLICT) {
            pickService.save(f.owner, f.bunchId, f.id, f.photoId, BundlePhotoPickRequest(true, version))
        }
    }

    @Test
    fun `a parent transaction must refresh a previously loaded pick after waiting for current state`() {
        val f = fixture()
        val version = service.get(f.owner, f.bunchId, f.id).revision
        val selected = pickService.save(f.owner, f.bunchId, f.id, f.photoId, BundlePhotoPickRequest(true, version))
        val cleared = pickService.save(
            f.owner, f.bunchId, f.id, f.photoId, BundlePhotoPickRequest(false, selected.version)
        )
        val loaded = CountDownLatch(1)
        val changed = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val stale = pool.submit<Boolean> {
                TransactionTemplate(transactions).execute {
                    pickRows.findByBundlePhotoIdAndMemberId(f.photoId, f.owner)!!
                    loaded.countDown()
                    check(changed.await(15, TimeUnit.SECONDS))
                    try {
                        pickService.save(
                            f.owner, f.bunchId, f.id, f.photoId, BundlePhotoPickRequest(false, cleared.version)
                        )
                        false
                    } catch (error: BusinessException) {
                        it.setRollbackOnly()
                        error.errorCode == ErrorCode.BUNDLE_VERSION_CONFLICT
                    }
                }!!
            }
            check(loaded.await(15, TimeUnit.SECONDS))
            pickService.save(f.owner, f.bunchId, f.id, f.photoId, BundlePhotoPickRequest(true, cleared.version))
            changed.countDown()
            assertThat(stale.get(20, TimeUnit.SECONDS)).isTrue()
            assertThat(detail(f).path("photos").first().path("picks").path("pickedByMe").asBoolean()).isTrue()
        } finally {
            changed.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `pick summaries see another member's committed state despite a parent transaction cache`() {
        val f = fixture()
        service.addParticipant(BundleActor(f.owner, "test"), f.bunchId, f.id, f.guest, "pick-cache-guest")
        val version = service.get(f.guest, f.bunchId, f.id).photos.single().picks!!.version
        val selected = pickService.save(f.guest, f.bunchId, f.id, f.photoId, BundlePhotoPickRequest(true, version))
        val cleared = pickService.save(
            f.guest, f.bunchId, f.id, f.photoId, BundlePhotoPickRequest(false, selected.version)
        )
        val loaded = CountDownLatch(1)
        val changed = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val response = pool.submit<BundlePhotoPicks> {
                TransactionTemplate(transactions).execute {
                    pickRows.findByBundlePhotoIdAndMemberId(f.photoId, f.guest)!!
                    loaded.countDown()
                    check(changed.await(15, TimeUnit.SECONDS))
                    pickService.save(f.owner, f.bunchId, f.id, f.photoId, BundlePhotoPickRequest(false, 0))
                }!!
            }
            check(loaded.await(15, TimeUnit.SECONDS))
            pickService.save(f.guest, f.bunchId, f.id, f.photoId, BundlePhotoPickRequest(true, cleared.version))
            changed.countDown()
            val displayed = response.get(20, TimeUnit.SECONDS)
            assertThat(displayed.count).isEqualTo(1)
            assertThat(displayed.members.map { it.memberId }).containsExactly(f.guest)
        } finally {
            changed.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `captionless published save preserves historical caption and starts new photos without captions or picks`() {
        val f = fixture()
        jdbc.update("UPDATE bundle_photos SET caption = '과거 캡션', caption_version = 2 WHERE id = ?", f.photoId)
        jdbc.update(
            """UPDATE bundles SET status = 'PUBLISHED', title = '발행본', visibility = 'PUBLIC',
                published_at = CURRENT_TIMESTAMP WHERE id = ?""",
            f.id
        )
        val revision = service.get(f.owner, f.bunchId, f.id).revision
        val saved = mvc.perform(put("/api/v1/bunches/${f.bunchId}/bundles/${f.id}/published")
            .header("Authorization", "Bearer ${token(f.owner)}")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{
                "expectedRevision":$revision,"title":"변경","visibility":"PUBLIC","date":null,
                "photos":[{"bundlePhotoId":${f.photoId}}],"locations":[]
            }"""))
            .andExpect(status().isOk()).andReturn()
        val retained = mapper.readTree(saved.response.contentAsString).path("data").path("photos").first()
        assertThat(retained.path("caption").asText()).isEqualTo("과거 캡션")
        assertThat(retained.path("captionVersion").asLong()).isEqualTo(2)
        write(f, """{"picked":true,"expectedVersion":0}""", 409)
        val addedAsset = assets.saveAndFlush(ImageAsset(
            publicId = "pick/${UUID.randomUUID()}",
            url = "https://images.example/new.jpg"
        ))
        assetIds += addedAsset.id
        val added = photos.saveAndFlush(Photo(
            bunch = bunchRows.findById(f.bunchId).orElseThrow(), imageAsset = addedAsset,
            fileHash = UUID.randomUUID().toString().replace("-", "").repeat(2),
            processingStatus = ProcessingStatus.READY
        ))
        val current = service.get(f.owner, f.bunchId, f.id)
        val result = service.editPublished(BundleActor(f.owner, "test"), f.bunchId, f.id,
            EditPublishedBundleRequest(current.revision, current.title, current.visibility!!, current.date,
                listOf(
                    PublishedBundlePhotoInput(bundlePhotoId = f.photoId),
                    PublishedBundlePhotoInput(photoId = added.id)
                ),
                emptyList()), "captionless-add")
        assertThat(result.photos.first().caption).isEqualTo("과거 캡션")
        assertThat(result.photos.last().caption).isEmpty()
        assertThat(result.photos.last().picks!!.count).isZero()
    }

    @Test
    fun `external published detail includes picks and still enforces bundle and bunch visibility`() {
        val f = fixture()
        val viewer = member()
        val version = service.get(f.owner, f.bunchId, f.id).revision
        pickService.save(f.owner, f.bunchId, f.id, f.photoId, BundlePhotoPickRequest(true, version))
        jdbc.update("UPDATE bunches SET visibility = 'PUBLIC' WHERE id = ?", f.bunchId)
        jdbc.update(
            """UPDATE bundles SET status = 'PUBLISHED', title = '발행본', visibility = 'PUBLIC',
                published_at = CURRENT_TIMESTAMP WHERE id = ?""",
            f.id
        )
        val path = "/api/v1/bunches/${f.bunchId}/published-bundles/${f.id}"
        val response = mvc.perform(get(path).header("Authorization", "Bearer ${token(viewer)}"))
            .andExpect(status().isOk()).andReturn().response
        val displayed = mapper.readTree(response.contentAsString).path("data").path("photos").first().path("picks")
        assertThat(displayed.path("count").asInt()).isEqualTo(1)
        assertThat(displayed.path("pickedByMe").asBoolean()).isFalse()
        assertThat(displayed.path("members").first().path("memberId").asLong()).isEqualTo(f.owner)
        assertThat(displayed.path("members").first().path("profileAvailable").asBoolean()).isTrue()
        jdbc.update("UPDATE bundles SET visibility = 'PRIVATE' WHERE id = ?", f.id)
        mvc.perform(get(path).header("Authorization", "Bearer ${token(viewer)}"))
            .andExpect(status().isNotFound())
        jdbc.update("UPDATE bundles SET visibility = 'PUBLIC' WHERE id = ?", f.id)
        jdbc.update("UPDATE bunches SET visibility = 'PRIVATE' WHERE id = ?", f.bunchId)
        mvc.perform(get(path).header("Authorization", "Bearer ${token(viewer)}"))
            .andExpect(status().isForbidden())
    }

    private fun fixture(): Fixture {
        val owner = member()
        val guest = member()
        val bunchId = create(owner).id
        join(guest, issue(owner, bunchId))
        val asset = assets.saveAndFlush(ImageAsset(
            publicId = "pick/${UUID.randomUUID()}",
            url = "https://images.example/pick.jpg"
        ))
        assetIds += asset.id
        val source = photos.saveAndFlush(Photo(
            bunch = bunchRows.findById(bunchId).orElseThrow(), imageAsset = asset,
            fileHash = UUID.randomUUID().toString().replace("-", "").repeat(2),
            processingStatus = ProcessingStatus.READY
        ))
        val bundle = service.create(
            BundleActor(owner, "test"), bunchId, UUID.randomUUID(), CreateBundleRequest(listOf(source.id))
        )
        return Fixture(owner, guest, bunchId, bundle.id, bundle.photos.single().id, source.id)
    }

    private fun path(f: Fixture) = "/api/v1/bunches/${f.bunchId}/bundles/${f.id}/photos/${f.photoId}/pick"

    private fun detail(f: Fixture, memberId: Long = f.owner): JsonNode {
        val response = mvc.perform(get("/api/v1/bunches/${f.bunchId}/bundles/${f.id}")
            .header("Authorization", "Bearer ${token(memberId)}")).andExpect(status().isOk()).andReturn()
        return mapper.readTree(response.response.contentAsString).path("data")
    }

    private fun write(f: Fixture, body: String, expectedStatus: Int = 200, memberId: Long = f.owner): JsonNode {
        val response = mvc.perform(put(path(f)).header("Authorization", "Bearer ${token(memberId)}")
            .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().response
        assertThat(response.status).isEqualTo(expectedStatus)
        return mapper.readTree(response.contentAsString).path(if (expectedStatus == 200) "data" else "error")
    }

    private fun token(memberId: Long): String {
        val sid = UUID.randomUUID().toString()
        sessions.save(sid, memberId, "test-refresh", 3600)
        credentials += memberId to sid
        return tokens.createAccessToken(memberId, "ROLE_USER", sid)
    }

    private data class Fixture(
        val owner: Long,
        val guest: Long,
        val bunchId: Long,
        val id: Long,
        val photoId: Long,
        val sourceId: Long
    )
}
