package com.bunch.member.service

import com.bunch.member.domain.Member
import com.bunch.member.domain.MemberRepository
import jakarta.persistence.EntityManager
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Profile("(local | dev) & !prod")
class DevMemberRegistry(
    private val members: MemberRepository,
    private val entityManager: EntityManager
) {
    @Transactional
    fun findOrCreate(account: String): Member {
        entityManager.createNativeQuery("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:identity, 0))")
            .setParameter("identity", "dev:$account")
            .singleResult
        return members.findByDevAccount(account) ?: members.saveAndFlush(Member(devAccount = account))
    }
}
