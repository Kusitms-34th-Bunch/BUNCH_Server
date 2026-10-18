package com.bunch.member.domain

import org.springframework.data.jpa.repository.JpaRepository

interface MemberConsentRepository : JpaRepository<MemberConsent, Long> {
    fun findAllByMemberId(memberId: Long): List<MemberConsent>
}
