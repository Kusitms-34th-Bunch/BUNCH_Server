package com.bunch.bundle.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface BundleRequestRepository : JpaRepository<BundleRequest, Long> {
    fun findAllByBundleIdAndOperationOrderByIdAsc(bundleId: Long, operation: String): List<BundleRequest>

    fun findByBunchIdAndMemberIdAndOperationAndRequestKey(
        bunchId: Long,
        memberId: Long,
        operation: String,
        requestKey: UUID
    ): BundleRequest?
}
