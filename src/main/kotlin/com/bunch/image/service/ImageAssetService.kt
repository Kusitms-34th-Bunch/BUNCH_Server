package com.bunch.image.service

import com.bunch.global.image.ProfileImageStorage
import com.bunch.global.image.StoredImage
import com.bunch.image.domain.ImageAsset
import com.bunch.image.domain.ImageAssetRepository
import org.springframework.dao.DataIntegrityViolationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException

@Service
class ImageAssetService(
    private val assets: ImageAssetRepository,
    private val storage: ProfileImageStorage,
    transactionManager: PlatformTransactionManager
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val cleanup = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    // Called inside the transaction that attaches the uploaded file to its owner.
    fun store(image: StoredImage): ImageAsset {
        requireTransaction()
        return assets.save(ImageAsset(publicId = image.publicId, url = image.url))
    }

    fun releaseAfterCommit(assetId: Long?) {
        if (assetId == null) return
        requireTransaction()
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                try {
                    deleteIfUnreferenced(assetId)
                } catch (_: Exception) {
                    // A committed owner change must never be reported as failed, or cause
                    // compensation to remove the new, now referenced file.
                    logger.warn("Image asset cleanup failed: assetId={}", assetId)
                }
            }
        })
    }

    private fun deleteIfUnreferenced(assetId: Long) {
        val publicId = try {
            cleanup.execute {
                val asset = assets.findLockedById(assetId) ?: return@execute null
                // Every owner uses a restrictive FK. This also protects references added by
                // future bundle_photos without a table registry or a reference counter.
                assets.delete(asset)
                assets.flush()
                asset.publicId
            }
        } catch (ex: DataIntegrityViolationException) {
            val referenced = generateSequence<Throwable>(ex) { it.cause }
                .filterIsInstance<SQLException>()
                .any { it.sqlState == "23503" }
            if (referenced) return
            throw ex
        }
        // The asset row is committed as deleted before its external file can be removed.
        storage.delete(publicId)
    }

    private fun requireTransaction() {
        check(TransactionSynchronizationManager.isActualTransactionActive())
        check(TransactionSynchronizationManager.isSynchronizationActive())
    }
}
