package com.bunch.member.domain
import com.bunch.global.oidc.AuthProvider
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.EntityGraph
interface MemberRepository : JpaRepository<Member, Long>
interface ExternalIdentityRepository : JpaRepository<ExternalIdentity, Long> {
    @EntityGraph(attributePaths = ["member"])
    fun findByProviderAndProviderSubject(provider: AuthProvider, providerSubject: String): ExternalIdentity?
}
