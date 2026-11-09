package com.bunch.member.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface MemberRepository : JpaRepository<Member, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select m from Member m where m.id = :id")
    fun findLockedById(id: Long): Member?
    fun existsByNickname(nickname: String): Boolean
    fun findByNickname(nickname: String): Member?

    @Query("select member from Member member left join fetch member.profileImageAsset where member.id in :ids")
    fun profiles(ids: Collection<Long>): List<Member>
}
