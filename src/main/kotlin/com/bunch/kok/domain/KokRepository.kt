package com.bunch.kok.domain

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface KokRepository : JpaRepository<Kok, Long> {
    fun findByBunchIdAndProviderAndExternalId(bunchId: Long, provider: String, externalId: String): Kok?

    @Query("""select pin from Kok pin where pin.bunchId = :bunchId
        and (:memberId is null or pin.pinnedByMemberId = :memberId)
        and (:hasLast = false or pin.pinnedAt < :lastTime or
            (pin.pinnedAt = :lastTime and pin.id < :lastId))
        order by pin.pinnedAt desc, pin.id desc""")
    fun page(
        bunchId: Long,
        memberId: Long?,
        hasLast: Boolean,
        lastTime: Instant?,
        lastId: Long?,
        pageable: Pageable
    ): List<Kok>

    @Query("""select pin.pinnedByMemberId as memberId, count(pin) as count from Kok pin
        where pin.bunchId = :bunchId group by pin.pinnedByMemberId""")
    fun countsByMember(bunchId: Long): List<MemberCount>

    interface MemberCount {
        val memberId: Long?
        val count: Long
    }

    // Provider cannot contain ':', so the key encoding is unambiguous even for external IDs containing ':'.
    @Query("""select pin from Kok pin where pin.bunchId = :bunchId
        and concat(pin.provider, ':', pin.externalId) in :keys""")
    fun findForKeys(bunchId: Long, keys: Collection<String>): List<Kok>

    @Modifying(flushAutomatically = true)
    @Query("delete from Kok pin where pin.id = :id and pin.bunchId = :bunchId")
    fun deleteOwned(id: Long, bunchId: Long): Int

    @Modifying(flushAutomatically = true)
    @Query("""delete from Kok pin where pin.sourceBundleId = :bundleId
        and pin.sourceLocationId in :locationIds""")
    fun deleteForRemovedLocations(bundleId: Long, locationIds: Collection<Long>): Int
}
