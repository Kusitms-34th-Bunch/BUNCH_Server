package com.bunch.bunch.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface BunchActionRequestRepository : JpaRepository<BunchActionRequest, Long> {
    fun findByMemberIdAndBunchIdAndOperationAndRequestKey(
        memberId: Long,
        bunchId: Long,
        operation: BunchActionOperation,
        requestKey: UUID
    ): BunchActionRequest?

    @Query("""select request from BunchActionRequest request
        where request.memberId = :memberId
        and request.bunchId = :bunchId
        and request.operation = :operation
        and request.requestKey = :requestKey
        and request.expiresAt > :now""")
    fun findActiveByMemberIdAndBunchIdAndOperationAndRequestKey(
        memberId: Long,
        bunchId: Long,
        operation: BunchActionOperation,
        requestKey: UUID,
        now: Instant
    ): BunchActionRequest?

    @Modifying
    @Query("""delete from BunchActionRequest request
        where request.bunchId = :bunchId and request.expiresAt <= :now""")
    fun deleteExpiredByBunchId(bunchId: Long, now: Instant): Int
}
