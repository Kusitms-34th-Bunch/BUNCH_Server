package com.bunch.bunch.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface BunchActionRequestRepository : JpaRepository<BunchActionRequest, Long> {
    fun findByMemberIdAndBunchIdAndOperationAndRequestKey(
        memberId: Long,
        bunchId: Long,
        operation: BunchActionOperation,
        requestKey: UUID
    ): BunchActionRequest?
}
