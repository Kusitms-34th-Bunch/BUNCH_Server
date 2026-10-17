package com.bunch.member.service
import com.bunch.member.domain.*
import com.bunch.global.oidc.AuthProvider
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
@Service
class SocialMemberRegistry(
    private val identities: ExternalIdentityRepository,
    private val members: MemberRepository,
    private val entityManager: EntityManager,
    transactionManager: PlatformTransactionManager
) {
    private val transaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }
    fun findOrCreate(provider: AuthProvider, subject: String, email: String?): Member = transaction.execute {
        // Serialize creation for this identity across application instances. Hash collisions only serialize unrelated identities.
        entityManager.createNativeQuery("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:identity, 0))")
            .setParameter("identity", "${provider.name}:$subject").singleResult
        identities.findByProviderAndProviderSubject(provider, subject)?.member ?: run {
            val member = members.saveAndFlush(Member(email = email))
            identities.saveAndFlush(ExternalIdentity(member = member, provider = provider,
                providerSubject = subject, emailAtLinkTime = email))
            member
        }
    }!!
}
