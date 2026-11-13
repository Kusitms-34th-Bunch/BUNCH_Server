package com.bunch.kok.service

import com.bunch.bundle.dto.BundleLocationsRemovedEvent
import com.bunch.kok.domain.KokRepository
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
class KokLocationListener(private val pins: KokRepository) {
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun removed(event: BundleLocationsRemovedEvent) {
        if (event.locationIds.isNotEmpty()) pins.deleteForRemovedLocations(event.bundleId, event.locationIds)
    }
}
