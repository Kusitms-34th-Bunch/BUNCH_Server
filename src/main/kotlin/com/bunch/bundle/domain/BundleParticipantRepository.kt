package com.bunch.bundle.domain

import org.springframework.data.jpa.repository.JpaRepository

interface BundleParticipantRepository : JpaRepository<BundleParticipant, Long> {
    fun findAllByBundleIdOrderByJoinedAtAscIdAsc(bundleId: Long): List<BundleParticipant>

    fun findByBundleIdAndMemberId(bundleId: Long, memberId: Long): BundleParticipant?
}
