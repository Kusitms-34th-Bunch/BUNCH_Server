package com.bunch.bundle.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface BundlePhotoPickRepository : JpaRepository<BundlePhotoPick, Long> {
    fun findAllByBundleId(bundleId: Long): List<BundlePhotoPick>

    fun findByBundlePhotoIdAndMemberId(bundlePhotoId: Long, memberId: Long): BundlePhotoPick?

    // Scalar state bypasses a parent transaction's already-managed, possibly stale pick entities.
    @Query("""
        select pick.bundlePhotoId as bundlePhotoId, pick.memberId as memberId,
            pick.picked as picked, pick.version as version
        from BundlePhotoPick pick where pick.bundleId = :bundleId
    """)
    fun findStatesByBundleId(bundleId: Long): List<State>

    interface State {
        val bundlePhotoId: Long
        val memberId: Long
        val picked: Boolean
        val version: Long
    }
}
