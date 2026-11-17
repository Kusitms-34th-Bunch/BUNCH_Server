package com.bunch.member.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface MemberConsentRepository : JpaRepository<MemberConsent, Long> {
    fun findAllByMemberId(memberId: Long): List<MemberConsent>

    fun findFirstByMemberIdAndTypeOrderByIdDesc(memberId: Long, type: TermType): MemberConsent?

    // The whole history of the member, the required terms and the optional consent alike.
    @Modifying(flushAutomatically = true)
    @Query("delete from MemberConsent consent where consent.memberId = :memberId")
    fun deleteByMemberId(memberId: Long): Int
}
