package com.bunch.bunch.domain

import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository

interface BunchListRepository : Repository<BunchMembership, Long> {
    @Query("""select membership from BunchMembership membership
        join fetch membership.bunch bunch
        left join fetch bunch.profileImageAsset
        where membership.member.id = :memberId
          and membership.status = com.bunch.bunch.domain.MembershipStatus.ACTIVE
        order by membership.lastUsedAt desc, membership.id desc""")
    fun findActiveByMemberId(memberId: Long): List<BunchMembership>
}
