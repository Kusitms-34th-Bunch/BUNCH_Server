package com.bunch.bundle.domain

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface BundleEditLeaseRepository : JpaRepository<BundleEditLease, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select lease from BundleEditLease lease " +
            "where lease.bundleId = :bundleId and lease.target = :target"
    )
    fun findLockedByBundleIdAndTarget(
        @Param("bundleId") bundleId: Long,
        @Param("target") target: String
    ): BundleEditLease?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select lease from BundleEditLease lease " +
            "where lease.bundleId = :bundleId order by lease.id asc"
    )
    fun findAllLockedByBundleId(@Param("bundleId") bundleId: Long): List<BundleEditLease>

    @Query(
        "select distinct lease.bundleId from BundleEditLease lease " +
            "where lease.connectionId = :connectionId and lease.releasedAt is null " +
            "order by lease.bundleId asc"
    )
    fun findBundleIdsByConnectionId(@Param("connectionId") connectionId: String): List<Long>

    fun findAllByBundleIdOrderByTargetAsc(bundleId: Long): List<BundleEditLease>
}
