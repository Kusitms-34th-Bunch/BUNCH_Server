package com.bunch.image

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.global.image.ProfileImageStorage
import com.bunch.image.domain.ImageAsset
import com.bunch.image.domain.ImageAssetRepository
import com.bunch.image.service.ImageAssetService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@ActiveProfiles("test")
class ImageAssetIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var assets: ImageAssetRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private val ownedAssets = mutableListOf<Long>()

    @AfterEach
    fun cleanAssets() {
        jdbc.execute("DROP TABLE IF EXISTS image_test_retained_references")
        ownedAssets.forEach { id -> jdbc.update("DELETE FROM image_assets WHERE id = ?", id) }
    }

    @Test
    fun `member bunch and photo references each protect the same external file`() {
        val storage = mock(ProfileImageStorage::class.java)
        val service = ImageAssetService(assets, storage, transactionManager)
        val owner = member()
        val bunch = create(owner)
        val asset = asset()
        jdbc.update("UPDATE members SET profile_image_asset_id = ? WHERE id = ?", asset.id, owner)
        jdbc.update("UPDATE bunches SET profile_image_asset_id = ? WHERE id = ?", asset.id, bunch.id)
        val photoId = jdbc.queryForObject(
            """INSERT INTO photos(bunch_id, image_asset_id, file_hash, processing_status, date_source, created_at)
                VALUES (?, ?, ?, 'READY', 'UNKNOWN', CURRENT_TIMESTAMP) RETURNING id""",
            Long::class.java,
            bunch.id,
            asset.id,
            "a".repeat(64)
        )!!
        try {
            release(service, asset.id)
            jdbc.update("UPDATE members SET profile_image_asset_id = NULL WHERE id = ?", owner)
            release(service, asset.id)
            jdbc.update("UPDATE bunches SET profile_image_asset_id = NULL WHERE id = ?", bunch.id)
            release(service, asset.id)
            verifyNoInteractions(storage)
            assertThat(assets.existsById(asset.id)).isTrue()
            doAnswer {
                assertThat(assets.existsById(asset.id)).isFalse()
                null
            }.`when`(storage).delete(asset.publicId)
            jdbc.update("DELETE FROM photos WHERE id = ?", photoId)
            release(service, asset.id)
            org.mockito.Mockito.verify(storage).delete(asset.publicId)
            assertThat(assets.existsById(asset.id)).isFalse()
        } finally {
            jdbc.update("DELETE FROM photos WHERE id = ?", photoId)
            jdbc.update("UPDATE members SET profile_image_asset_id = NULL WHERE id = ?", owner)
            jdbc.update("UPDATE bunches SET profile_image_asset_id = NULL WHERE id = ?", bunch.id)
        }
    }

    @Test
    fun `rollback does not schedule a file deletion and repeated release deletes once`() {
        val asset = asset()
        val storage = mock(ProfileImageStorage::class.java)
        val service = ImageAssetService(assets, storage, transactionManager)
        TransactionTemplate(transactionManager).execute {
            service.releaseAfterCommit(asset.id)
            it.setRollbackOnly()
        }
        assertThat(assets.existsById(asset.id)).isTrue()
        verifyNoInteractions(storage)
        release(service, asset.id)
        release(service, asset.id)
        org.mockito.Mockito.verify(storage, org.mockito.Mockito.times(1)).delete(asset.publicId)
    }

    @Test
    fun `restrictive FK protects a future retaining owner without a registry`() {
        // Fixture contract only: this is not a bundle table or real gallery API integration.
        jdbc.execute("""CREATE TABLE image_test_retained_references (
            id BIGINT PRIMARY KEY, image_asset_id BIGINT NOT NULL REFERENCES image_assets(id))""")
        val asset = asset()
        val storage = mock(ProfileImageStorage::class.java)
        val service = ImageAssetService(assets, storage, transactionManager)
        jdbc.update("INSERT INTO image_test_retained_references VALUES (1, ?)", asset.id)
        release(service, asset.id)
        assertThat(assets.existsById(asset.id)).isTrue()
        verifyNoInteractions(storage)
        assertThatThrownBy { assets.deleteById(asset.id) }.isInstanceOf(DataIntegrityViolationException::class.java)
        jdbc.update("DELETE FROM image_test_retained_references")
        release(service, asset.id)
        org.mockito.Mockito.verify(storage).delete(asset.publicId)
    }

    @Test
    fun `cleanup failure after commit does not roll back owner change or escape to compensation`() {
        val owner = member()
        val asset = asset()
        jdbc.update("UPDATE members SET profile_image_asset_id = ? WHERE id = ?", asset.id, owner)
        val storage = mock(ProfileImageStorage::class.java)
        doAnswer { throw IllegalStateException("sensitive upstream detail") }.`when`(storage).delete(asset.publicId)
        val service = ImageAssetService(assets, storage, transactionManager)
        val logger = LoggerFactory.getLogger(ImageAssetService::class.java) as Logger
        val events = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(events)
        try {
            TransactionTemplate(transactionManager).execute {
                jdbc.update("UPDATE members SET profile_image_asset_id = NULL WHERE id = ?", owner)
                service.releaseAfterCommit(asset.id)
            }
            val failure = events.list.single()
            assertThat(failure.formattedMessage).isEqualTo("Image asset cleanup failed: assetId=${asset.id}")
            assertThat(failure.throwableProxy).isNull()
        } finally {
            logger.detachAppender(events)
            events.stop()
        }
        assertThat(assets.existsById(asset.id)).isFalse()
        assertThat(jdbc.queryForObject(
            "SELECT profile_image_asset_id IS NULL FROM members WHERE id = ?", Boolean::class.java, owner
        )).isTrue()
    }

    @Test
    fun `reference committed during cleanup prevents external file removal`() {
        val asset = asset()
        val owner = member()
        val storage = mock(ProfileImageStorage::class.java)
        val service = ImageAssetService(assets, storage, transactionManager)
        val referenced = CountDownLatch(1)
        val allowCommit = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val writer = executor.submit {
                TransactionTemplate(transactionManager).execute {
                    jdbc.update("UPDATE members SET profile_image_asset_id = ? WHERE id = ?", asset.id, owner)
                    referenced.countDown()
                    check(allowCommit.await(10, TimeUnit.SECONDS))
                }
            }
            check(referenced.await(10, TimeUnit.SECONDS))
            val cleanupStarted = CountDownLatch(1)
            val releaser = executor.submit {
                cleanupStarted.countDown()
                release(service, asset.id)
            }
            check(cleanupStarted.await(10, TimeUnit.SECONDS))
            allowCommit.countDown()
            writer.get(10, TimeUnit.SECONDS)
            releaser.get(10, TimeUnit.SECONDS)
            assertThat(assets.existsById(asset.id)).isTrue()
            verifyNoInteractions(storage)
        } finally {
            allowCommit.countDown()
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
            jdbc.update("UPDATE members SET profile_image_asset_id = NULL WHERE id = ?", owner)
        }
    }

    private fun asset(): ImageAsset = assets.saveAndFlush(
        ImageAsset(publicId = "test/${UUID.randomUUID()}", url = "https://images.example/retained.jpg")
    ).also { ownedAssets.add(it.id) }

    private fun release(service: ImageAssetService, id: Long) {
        TransactionTemplate(transactionManager).execute { service.releaseAfterCommit(id) }
    }
}
