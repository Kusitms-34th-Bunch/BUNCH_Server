package com.bunch.bundle.domain

import org.springframework.data.jpa.repository.JpaRepository

interface BundleCommentRepository : JpaRepository<BundleComment, Long> {
    fun findAllByBundleIdOrderByIdAsc(bundleId: Long): List<BundleComment>

    fun findByBundleIdAndMemberId(bundleId: Long, memberId: Long): BundleComment?
}
