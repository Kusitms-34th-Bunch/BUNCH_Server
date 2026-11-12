package com.bunch.member.domain

import com.bunch.global.oidc.AuthProvider
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface ExternalIdentityRepository : JpaRepository<ExternalIdentity, Long> {
    @EntityGraph(attributePaths = ["member"])
    fun findByProviderAndProviderSubject(provider: AuthProvider, providerSubject: String): ExternalIdentity?

    @Modifying(flushAutomatically = true)
    @Query("delete from ExternalIdentity identity where identity.member.id = :memberId")
    fun deleteByMemberId(memberId: Long): Int
}
