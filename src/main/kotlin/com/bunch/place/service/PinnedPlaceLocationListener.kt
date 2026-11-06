package com.bunch.place.service

import com.bunch.bundle.service.BundleLocationsRemovedEvent
import com.bunch.place.domain.PinnedPlaceRepository
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
class PinnedPlaceLocationListener(private val pins: PinnedPlaceRepository) {
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun removed(event: BundleLocationsRemovedEvent) {
        if (event.locationIds.isNotEmpty()) pins.deleteForRemovedLocations(event.bundleId, event.locationIds)
    }
}
