package com.bunch.social.domain

import com.bunch.member.domain.Member
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository

interface SocialCommentAuthorRepository : Repository<Member, Long> {
    @Query("select member from Member member left join fetch member.profileImageAsset where member.id in :ids")
    fun profiles(ids: Collection<Long>): List<Member>
}
