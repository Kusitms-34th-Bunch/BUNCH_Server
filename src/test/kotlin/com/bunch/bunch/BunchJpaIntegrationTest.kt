package com.bunch.bunch

import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bunch.domain.JoinOutcome
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class BunchJpaIntegrationTest : BunchIntegrationSupport() {
    @Autowired private lateinit var memberships: BunchMembershipRepository
    @Autowired private lateinit var entityManager: EntityManager
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `membership reads do not eagerly load bunch or member`() {
        val owner = member()
        val bunch = create(owner)
        TransactionTemplate(transactionManager).execute {
            val membership = memberships.findByMemberIdAndBunchIdAndStatus(owner, bunch.id)!!
            val persistence = entityManager.entityManagerFactory.persistenceUnitUtil
            assertThat(persistence.isLoaded(membership, "bunch")).isFalse()
            assertThat(persistence.isLoaded(membership, "member")).isFalse()
            assertThat(membership.bunch.id).isEqualTo(bunch.id)
            assertThat(membership.member.id).isEqualTo(owner)
            assertThat(persistence.isLoaded(membership, "bunch")).isFalse()
            assertThat(persistence.isLoaded(membership, "member")).isFalse()
        }
    }

    @Test
    fun `legacy JDBC join request JSON remains replayable and new keys preserve it`() {
        val owner = member()
        val visitor = member()
        val bunch = create(owner)
        val invitation = issue(owner, bunch.id)
        join(visitor, invitation)
        val invitationId = jdbc.queryForObject("SELECT id FROM bunch_invitations WHERE code = ?", Long::class.java, invitation.code)!!
        val legacyKey = UUID.randomUUID()
        jdbc.update("""UPDATE bunch_memberships SET join_requests =
            jsonb_build_object(CAST(? AS text), jsonb_build_object('fingerprint', CAST(? AS text), 'outcome', 'JOINED'))
            WHERE bunch_id = ? AND member_id = ?""", legacyKey.toString(), "invitation:$invitationId", bunch.id, visitor)
        val newKey = UUID.randomUUID()
        assertThat(join(visitor, invitation, newKey).outcome).isEqualTo(JoinOutcome.ALREADY_MEMBER)
        jdbc.update("UPDATE bunch_invitations SET revoked_at = CURRENT_TIMESTAMP WHERE id = ?", invitationId)
        assertThat(join(visitor, invitation, legacyKey).outcome).isEqualTo(JoinOutcome.JOINED)
        assertThat(join(visitor, invitation, newKey).outcome).isEqualTo(JoinOutcome.ALREADY_MEMBER)
        assertThat(count("bunch_memberships")).isEqualTo(2)
        assertThat(count("bunch_join_notifications")).isEqualTo(1)
    }
}
