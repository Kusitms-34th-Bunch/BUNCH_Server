package com.bunch.bunch.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface InvitationRepository : JpaRepository<Invitation, Long> {
    fun findByToken(token: String): Invitation?
    fun findByCode(code: String): Invitation?
    fun findByBunchIdAndRevokedAtIsNull(bunchId: Long): Invitation?

    // A token/code collision must allow another attempt in the same transaction.
    @Modifying(flushAutomatically = true)
    @Query(value = """INSERT INTO bunch_invitations(bunch_id, issuer_member_id, token, code, created_at, expires_at)
        VALUES (:bunchId, :memberId, :token, :code, :now, :expiresAt)
        ON CONFLICT DO NOTHING""", nativeQuery = true)
    fun insertIfAvailable(bunchId: Long, memberId: Long, token: String, code: String, now: Instant, expiresAt: Instant): Int
}
