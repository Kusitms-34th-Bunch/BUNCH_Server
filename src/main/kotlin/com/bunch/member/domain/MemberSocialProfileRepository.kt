package com.bunch.member.domain

import com.bunch.bunch.domain.Bunch
import com.bunch.feed.domain.FeedAccessRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository

interface MemberSocialProfileRepository : Repository<Member, Long> {
    @Query("""select member from Member member left join fetch member.profileImageAsset
        where member.id = :memberId
        and member.accountStatus = com.bunch.member.domain.AccountStatus.ACTIVE
        and member.signupStatus = com.bunch.member.domain.SignupStatus.COMPLETED""")
    fun findPublicMember(memberId: Long): Member?

    @Query("""select bunch from BunchMembership membership join membership.bunch bunch
        left join fetch bunch.profileImageAsset
        where membership.member.id = :memberId
        and membership.status = com.bunch.bunch.domain.MembershipStatus.ACTIVE and """ +
        FeedAccessRepository.VISIBLE_BUNCH)
    fun findVisibleActiveBunches(memberId: Long, currentBunchId: Long?): List<Bunch>
}
