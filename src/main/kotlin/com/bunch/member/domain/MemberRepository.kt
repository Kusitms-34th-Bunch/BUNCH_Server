package com.bunch.member.domain
import com.bunch.global.oidc.AuthProvider
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.EntityGraph
interface MemberRepository : JpaRepository<Member, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select m from Member m where m.id = :id")
    fun findLockedById(id: Long): Member?
    fun existsByNickname(nickname: String): Boolean
}
interface ExternalIdentityRepository : JpaRepository<ExternalIdentity, Long> {
    @EntityGraph(attributePaths = ["member"])
    fun findByProviderAndProviderSubject(provider: AuthProvider, providerSubject: String): ExternalIdentity?
}
