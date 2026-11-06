package com.bunch.bundle.domain

import org.springframework.data.jpa.repository.JpaRepository

interface BundleLocationRepository : JpaRepository<BundleLocation, Long> {
    fun findAllByBundleIdOrderByPositionAscIdAsc(bundleId: Long): List<BundleLocation>

    fun findAllByBundleIdInOrderByBundleIdAscPositionAscIdAsc(bundleIds: Collection<Long>): List<BundleLocation>
}
