package com.bunch.bundle.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface BundleCommentRepository : JpaRepository<BundleComment, Long> {
    fun findAllByBundleIdOrderByIdAsc(bundleId: Long): List<BundleComment>

    fun findAllByBundleIdInOrderByBundleIdAscIdAsc(bundleIds: Collection<Long>): List<BundleComment>

    fun findByBundleIdAndMemberId(bundleId: Long, memberId: Long): BundleComment?

    @Modifying(flushAutomatically = true)
    @Query("update BundleComment c set c.content = '' where c.memberId = :memberId and c.content <> ''")
    fun clearContentByMemberId(memberId: Long): Int
}
