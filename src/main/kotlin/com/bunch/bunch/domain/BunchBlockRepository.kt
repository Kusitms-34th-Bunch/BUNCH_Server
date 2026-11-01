package com.bunch.bunch.domain

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface BunchBlockRepository : JpaRepository<BunchBlock, Long> {
    fun findByBlockerBunchIdAndBlockedBunchId(blockerBunchId: Long, blockedBunchId: Long): BunchBlock?

    @Query("""select count(b) > 0 from BunchBlock b where
        (b.blockerBunchId = :firstId and b.blockedBunchId = :secondId) or
        (b.blockerBunchId = :secondId and b.blockedBunchId = :firstId)""")
    fun blocked(firstId: Long, secondId: Long): Boolean

    fun findAllByBlockerBunchId(blockerBunchId: Long, pageable: Pageable): Page<BunchBlock>
}
