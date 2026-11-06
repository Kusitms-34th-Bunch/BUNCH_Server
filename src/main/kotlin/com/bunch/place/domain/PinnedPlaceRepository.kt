package com.bunch.place.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface PinnedPlaceRepository : JpaRepository<PinnedPlace, Long> {
    fun findByBunchIdAndProviderAndExternalId(bunchId: Long, provider: String, externalId: String): PinnedPlace?

    // Provider cannot contain ':', so the key encoding is unambiguous even for external IDs containing ':'.
    @Query("""select pin from PinnedPlace pin where pin.bunchId = :bunchId
        and concat(pin.provider, ':', pin.externalId) in :keys""")
    fun findForKeys(bunchId: Long, keys: Collection<String>): List<PinnedPlace>

    @Modifying(flushAutomatically = true)
    @Query("delete from PinnedPlace pin where pin.id = :id and pin.bunchId = :bunchId")
    fun deleteOwned(id: Long, bunchId: Long): Int

    @Modifying(flushAutomatically = true)
    @Query("""delete from PinnedPlace pin where pin.sourceBundleId = :bundleId
        and pin.sourceLocationId in :locationIds""")
    fun deleteForRemovedLocations(bundleId: Long, locationIds: Collection<Long>): Int
}
