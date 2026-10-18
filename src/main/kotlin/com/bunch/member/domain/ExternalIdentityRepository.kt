package com.bunch.member.domain

import com.bunch.global.oidc.AuthProvider
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository

interface ExternalIdentityRepository : JpaRepository<ExternalIdentity, Long> {
    @EntityGraph(attributePaths = ["member"])
    fun findByProviderAndProviderSubject(provider: AuthProvider, providerSubject: String): ExternalIdentity?
}
