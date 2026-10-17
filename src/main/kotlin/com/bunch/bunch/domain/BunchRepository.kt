package com.bunch.bunch.domain

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class BunchRepository(private val jdbc: JdbcTemplate) {
    fun find(id: Long): Bunch? = jdbc.query("SELECT * FROM bunches WHERE id = ?", bunchMapper, id).firstOrNull()

    fun findLocked(id: Long): Bunch? = jdbc.query(
        "SELECT * FROM bunches WHERE id = ? FOR UPDATE", bunchMapper, id
    ).firstOrNull()

    fun findCreation(memberId: Long, key: UUID): Bunch? = jdbc.query(
        "SELECT * FROM bunches WHERE created_by = ? AND creation_request_key = ?", bunchMapper, memberId, key
    ).firstOrNull()

    fun nameExists(name: String): Boolean = jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM bunches WHERE name = ?)", Boolean::class.java, name
    ) == true

    fun create(
        memberId: Long, key: UUID, fingerprint: String, name: String, introduction: String,
        visibility: Visibility, characterType: CharacterType, now: Instant
    ): Bunch? = jdbc.query(
        """INSERT INTO bunches(name, introduction, visibility, character_type, created_by,
            creation_request_key, creation_fingerprint, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (name) DO NOTHING RETURNING *""",
        bunchMapper, name, introduction, visibility.name, characterType.name, memberId, key, fingerprint, Timestamp.from(now)
    ).firstOrNull()

    fun activeMembership(memberId: Long, bunchId: Long): Membership? = jdbc.query(
        "SELECT * FROM bunch_memberships WHERE member_id = ? AND bunch_id = ? AND status = 'ACTIVE'",
        membershipMapper, memberId, bunchId
    ).firstOrNull()

    fun countAffiliations(memberId: Long): Int = jdbc.queryForObject(
        "SELECT count(*) FROM bunch_memberships WHERE member_id = ? AND status = 'ACTIVE'",
        Int::class.java, memberId
    )!!

    fun countMembers(bunchId: Long): Int = jdbc.queryForObject(
        "SELECT count(*) FROM bunch_memberships WHERE bunch_id = ? AND status = 'ACTIVE'", Int::class.java, bunchId
    )!!

    fun addMembership(memberId: Long, bunchId: Long, role: MembershipRole, now: Instant): Membership = jdbc.query(
        """INSERT INTO bunch_memberships(bunch_id, member_id, role, status, joined_at, role_granted_at, last_used_at)
            VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?) RETURNING *""",
        membershipMapper, bunchId, memberId, role.name, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
    ).single()

    fun markUsed(membershipId: Long, now: Instant) {
        jdbc.update("UPDATE bunch_memberships SET last_used_at = ? WHERE id = ?", Timestamp.from(now), membershipId)
    }

    fun defaultBunchId(memberId: Long): Long? = jdbc.query(
        """SELECT bunch_id FROM bunch_memberships WHERE member_id = ? AND status = 'ACTIVE'
            ORDER BY last_used_at DESC, id DESC LIMIT 1""",
        RowMapper { rs, _ -> rs.getLong("bunch_id") }, memberId
    ).firstOrNull()

    fun findJoinReplay(memberId: Long, key: UUID): JoinReplay? = jdbc.query(
        """SELECT id, bunch_id, jsonb_extract_path_text(join_requests, ?, 'fingerprint') AS fingerprint,
            jsonb_extract_path_text(join_requests, ?, 'outcome') AS outcome
            FROM bunch_memberships WHERE member_id = ? AND jsonb_exists(join_requests, ?)""",
        RowMapper { rs, _ -> JoinReplay(rs.getLong("id"), rs.getLong("bunch_id"), rs.getString("fingerprint"), JoinOutcome.valueOf(rs.getString("outcome"))) },
        key.toString(), key.toString(), memberId, key.toString()
    ).firstOrNull()

    fun rememberJoin(membershipId: Long, key: UUID, fingerprint: String, outcome: JoinOutcome) {
        jdbc.update(
            """UPDATE bunch_memberships SET join_requests = join_requests ||
                jsonb_build_object(CAST(? AS text), jsonb_build_object('fingerprint', CAST(? AS text), 'outcome', CAST(? AS text)))
                WHERE id = ?""", key.toString(), fingerprint, outcome.name, membershipId
        )
    }

    fun updateImage(bunchId: Long, publicId: String?, url: String?) {
        jdbc.update("UPDATE bunches SET image_public_id = ?, image_url = ? WHERE id = ?", publicId, url, bunchId)
    }

    private val bunchMapper = RowMapper { rs, _ ->
        Bunch(rs.getLong("id"), rs.getString("name"), rs.getString("introduction"),
            Visibility.valueOf(rs.getString("visibility")), CharacterType.valueOf(rs.getString("character_type")),
            rs.getString("image_public_id"), rs.getString("image_url"), rs.getString("creation_fingerprint"))
    }
    private val membershipMapper = RowMapper { rs, _ ->
        Membership(rs.getLong("id"), rs.getLong("bunch_id"), rs.getLong("member_id"),
            MembershipRole.valueOf(rs.getString("role")), MembershipStatus.valueOf(rs.getString("status")))
    }
}
