package com.bunch.bunch.domain

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

@Repository
class InvitationRepository(private val jdbc: JdbcTemplate) {
    fun findById(id: Long): Invitation? = jdbc.query("SELECT * FROM bunch_invitations WHERE id = ?", mapper, id).firstOrNull()
    fun findByToken(token: String): Invitation? = jdbc.query("SELECT * FROM bunch_invitations WHERE token = ?", mapper, token).firstOrNull()
    fun findByCode(code: String): Invitation? = jdbc.query("SELECT * FROM bunch_invitations WHERE code = ?", mapper, code).firstOrNull()
    fun findNonrevoked(bunchId: Long): Invitation? = jdbc.query(
        "SELECT * FROM bunch_invitations WHERE bunch_id = ? AND revoked_at IS NULL", mapper, bunchId
    ).firstOrNull()

    fun revoke(bunchId: Long, now: Instant) {
        jdbc.update("UPDATE bunch_invitations SET revoked_at = ? WHERE bunch_id = ? AND revoked_at IS NULL", Timestamp.from(now), bunchId)
    }

    fun issue(bunchId: Long, memberId: Long, token: String, code: String, now: Instant, expiresAt: Instant): Invitation? = jdbc.query(
        """INSERT INTO bunch_invitations(bunch_id, issuer_member_id, token, code, created_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING RETURNING *""",
        mapper, bunchId, memberId, token, code, Timestamp.from(now), Timestamp.from(expiresAt)
    ).firstOrNull()

    fun inviterNickname(invitation: Invitation): String? = jdbc.query(
        """SELECT m.nickname FROM members m JOIN bunch_memberships bm ON bm.member_id = m.id
            WHERE m.id = ? AND m.account_status = 'ACTIVE' AND bm.bunch_id = ? AND bm.status = 'ACTIVE'""",
        RowMapper { rs, _ -> rs.getString("nickname") }, invitation.issuerMemberId, invitation.bunchId
    ).firstOrNull()

    private val mapper = RowMapper { rs, _ ->
        Invitation(rs.getLong("id"), rs.getLong("bunch_id"), rs.getLong("issuer_member_id"),
            rs.getString("token"), rs.getString("code"), rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("revoked_at")?.toInstant())
    }
}
