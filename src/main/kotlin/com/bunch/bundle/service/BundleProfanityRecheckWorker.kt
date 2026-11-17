package com.bunch.bundle.service

import com.bunch.bundle.domain.BundleRepository
import com.bunch.global.profanity.ProfanityFilter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class BundleProfanityRecheckWorker(
    private val bundles: BundleRepository,
    private val service: BundleService,
    private val profanity: ProfanityFilter,
    @Value("\${profanity.recheck-enabled:true}")
    private val enabled: Boolean
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private var version: String? = null
    private var afterId = 0L

    @Scheduled(fixedDelayString = "\${profanity.recheck-interval-ms:1000}")
    fun poll() {
        if (!enabled) return
        try {
            runOnce()
        } catch (ex: Exception) {
            logger.warn("Profanity draft scan failed type={}", ex.javaClass.simpleName)
        }
    }

    /** A cursor moves past failed rows; reaching the end starts a new pass to retry them. */
    @Synchronized
    fun runOnce(): Int {
        val current = profanity.current().version
        if (version != current) {
            version = current
            afterId = 0
        }
        var ids = bundles.findDraftIdsForProfanityRecheck(current, afterId, 100)
        if (ids.isEmpty() && afterId != 0L) {
            afterId = 0
            ids = bundles.findDraftIdsForProfanityRecheck(current, afterId, 100)
        }
        var examined = 0
        ids.forEach { id ->
            try {
                if (service.recheckProfanity(id)) examined++
            } catch (ex: Exception) {
                logger.warn("Profanity draft recheck failed bundleId={} type={}", id, ex.javaClass.simpleName)
            }
            afterId = id
        }
        if (ids.size < 100) afterId = 0
        return examined
    }
}
