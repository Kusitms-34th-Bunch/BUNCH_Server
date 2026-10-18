package com.bunch.bunch

import com.bunch.bunch.domain.InviteEligibility
import com.bunch.bunch.domain.JoinOutcome
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataAccessException
import org.springframework.test.context.ActiveProfiles
import java.time.Duration
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class InvitationIntegrationTest : BunchIntegrationSupport() {
    @Autowired private lateinit var mapper: ObjectMapper

    @Test
    fun `concurrent issuance reuses one invitation and preserves original issuer`() {
        val owner = member()
        val other = member()
        val bunch = create(owner)
        val first = issue(owner, bunch.id)
        join(other, first)
        val results = concurrently(List(6) { index -> { issue(if (index % 2 == 0) owner else other, bunch.id) } })
            .map { it.getOrThrow() }
        assertThat(results).allMatch { it == first }
        assertThat(count("bunch_invitations")).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT issuer_member_id FROM bunch_invitations", Long::class.java)).isEqualTo(owner)
        assertThat(invitations.verify(other, first.code).card.inviterNickname).isEqualTo(members.findById(owner).get().nickname)
    }

    @Test
    fun `concurrent first issuance has one nonrevoked invitation with exact seven day expiry`() {
        val owner = member()
        val bunch = create(owner)
        val results = concurrently(List(6) { { issue(owner, bunch.id) } }).map { it.getOrThrow() }
        assertThat(results.toSet()).hasSize(1)
        val row = jdbc.queryForMap("SELECT created_at, expires_at FROM bunch_invitations")
        val duration = Duration.between((row["created_at"] as java.sql.Timestamp).toInstant(), (row["expires_at"] as java.sql.Timestamp).toInstant())
        assertThat(duration).isEqualTo(Duration.ofDays(7))
        assertThat(results.first().url.substringAfterLast('/')).matches("[A-Za-z0-9_-]{43}")
        assertThat(results.first().code).matches("[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{8}")
    }

    @Test
    fun `expiration revokes old row before reissue and preserves old invitation identity`() {
        val owner = member()
        val visitor = member()
        val bunch = create(owner)
        val first = issue(owner, bunch.id)
        expireInvitations()
        assertError(ErrorCode.INVALID_INVITATION) { invitations.verify(visitor, first.code) }
        val second = issue(owner, bunch.id)
        assertThat(second.code).isNotEqualTo(first.code)
        assertThat(count("bunch_invitations")).isEqualTo(2)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bunch_invitations WHERE revoked_at IS NULL", Long::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM bunch_invitations WHERE code = ?", Boolean::class.java, first.code)).isTrue()
        assertError(ErrorCode.INVALID_INVITATION) { invitations.verify(visitor, first.url) }
    }

    @Test
    fun `token code and configured URL resolve same card and disallow foreign or altered URLs`() {
        val owner = member()
        val visitor = member()
        val invite = issue(owner, create(owner).id)
        val token = invite.url.substringAfterLast('/')
        val byToken = invitations.verify(visitor, token)
        assertThat(invitations.verify(visitor, "  ${invite.code.lowercase()}  ")).isEqualTo(byToken)
        assertThat(invitations.verify(visitor, "  ${invite.url}  ")).isEqualTo(byToken)
        listOf(
            "https://attacker.example/invite/$token",
            "http://localhost:3000/elsewhere/$token",
            "http://attacker@localhost:3000/invite/$token",
            "${invite.url}?redirect=elsewhere",
            "${invite.url}#fragment",
            "http://localhost:3000/invite/../invite/$token",
            "http://localhost:3000/invite/%2F$token"
        ).forEach { altered -> assertError(ErrorCode.INVALID_INVITATION) { invitations.verify(visitor, altered) } }
    }

    @Test
    fun `public preview reveals only minimal card even for private full bunch and hides inactive issuer`() {
        val owner = member()
        val bunch = create(owner)
        val invite = issue(owner, bunch.id)
        repeat(7) { join(member(), invite) }
        val card = invitations.publicPreview(invite.url.substringAfterLast('/'), "test-public-card")
        val fields = mapper.valueToTree<JsonNode>(card).fieldNames().asSequence().toSet()
        assertThat(fields).containsExactlyInAnyOrder("name", "introduction", "imageUrl", "inviterNickname", "code")
        assertThat(card.inviterNickname).isNotNull()
        jdbc.update("UPDATE bunch_memberships SET status = 'LEFT' WHERE member_id = ?", owner)
        assertThat(invitations.publicPreview(invite.code, "test-public-card").inviterNickname).isNull()
        jdbc.update("UPDATE bunch_memberships SET status = 'ACTIVE' WHERE member_id = ?", owner)
        jdbc.update("UPDATE members SET account_status = 'WITHDRAWN' WHERE id = ?", owner)
        assertThat(invitations.publicPreview(invite.code, "test-public-card").inviterNickname).isNull()
    }

    @Test
    fun `join notifies each existing member once and replay never duplicates membership or notifications`() {
        val owner = member()
        val existing = member()
        val newcomer = member()
        val invite = issue(owner, create(owner).id)
        join(existing, invite)
        val before = count("bunch_join_notifications")
        val key = UUID.randomUUID()
        val first = join(newcomer, invite, key)
        assertThat(first.outcome).isEqualTo(JoinOutcome.JOINED)
        assertThat(count("bunch_join_notifications") - before).isEqualTo(2)
        val recipients = jdbc.queryForList("SELECT recipient_member_id FROM bunch_join_notifications WHERE joined_member_id = ?", Long::class.java, newcomer)
        assertThat(recipients).containsExactlyInAnyOrder(owner, existing)
        val membershipId = jdbc.queryForObject("SELECT id FROM bunch_memberships WHERE member_id = ?", Long::class.java, newcomer)
        assertThat(invitations.join(newcomer, key, invite.code.lowercase())).isEqualTo(first)
        assertThat(invitations.join(newcomer, key, invite.url.substringAfterLast('/'))).isEqualTo(first)
        assertThat(count("bunch_join_notifications")).isEqualTo(before + 2)
        assertThat(jdbc.queryForObject("SELECT id FROM bunch_memberships WHERE member_id = ?", Long::class.java, newcomer)).isEqualTo(membershipId)
        assertThat(bunches.defaultBunchId(newcomer)).isEqualTo(first.bunch.id)
    }

    @Test
    fun `notification failure rolls back membership usage and idempotency result and can retry`() {
        val owner = member()
        val newcomer = member()
        val invite = issue(owner, create(owner).id)
        val key = UUID.randomUUID()
        rejectWrite("bunch_test_reject_notification", "bunch_join_notifications", "INSERT")
        assertThatThrownBy { join(newcomer, invite, key) }.isInstanceOf(DataAccessException::class.java)
        assertThat(count("bunch_memberships")).isEqualTo(1)
        assertThat(count("bunch_join_notifications")).isZero()
        assertThat(bunches.defaultBunchId(newcomer)).isNull()
        jdbc.execute("DROP TRIGGER bunch_test_reject_notification ON bunch_join_notifications")
        assertThat(join(newcomer, invite, key).outcome).isEqualTo(JoinOutcome.JOINED)
        assertThat(count("bunch_memberships")).isEqualTo(2)
        assertThat(count("bunch_join_notifications")).isEqualTo(1)
    }

    @Test
    fun `join replay survives revocation expiration limits and returns current bunch fields`() {
        val owner = member()
        val newcomer = member()
        val bunch = create(owner)
        val invite = issue(owner, bunch.id)
        val key = UUID.randomUUID()
        val first = join(newcomer, invite, key)
        repeat(9) { create(newcomer) }
        repeat(6) { join(member(), invite) }
        jdbc.update("UPDATE bunch_invitations SET revoked_at = CURRENT_TIMESTAMP")
        expireInvitations()
        val newName = uniqueName()
        jdbc.update("UPDATE bunches SET name = ?, image_url = ? WHERE id = ?", newName, "https://images.example/current.jpg", bunch.id)
        val replay = invitations.join(newcomer, key, " ${invite.code.lowercase()} ")
        assertThat(replay.outcome).isEqualTo(JoinOutcome.JOINED)
        assertThat(replay.bunch.id).isEqualTo(first.bunch.id)
        assertThat(replay.bunch.name).isEqualTo(newName)
        assertThat(replay.bunch.imageUrl).isEqualTo("https://images.example/current.jpg")
        assertError(ErrorCode.INVALID_INVITATION) { join(newcomer, invite) }
    }

    @Test
    fun `existing membership precedes both limits after validity and remembers multiple already member keys`() {
        val owner = member()
        val participant = member()
        val invite = issue(owner, create(owner).id)
        join(participant, invite)
        repeat(9) { create(participant) }
        repeat(6) { join(member(), invite) }
        assertThat(invitations.verify(participant, invite.code).eligibility).isEqualTo(InviteEligibility.ALREADY_MEMBER)
        val keys = List(3) { UUID.randomUUID() }
        keys.forEach { key -> assertThat(join(participant, invite, key).outcome).isEqualTo(JoinOutcome.ALREADY_MEMBER) }
        val notifications = count("bunch_join_notifications")
        jdbc.update("UPDATE bunch_invitations SET revoked_at = CURRENT_TIMESTAMP")
        keys.forEach { key -> assertThat(invitations.join(participant, key, invite.code).outcome).isEqualTo(JoinOutcome.ALREADY_MEMBER) }
        assertThat(count("bunch_join_notifications")).isEqualTo(notifications)
        assertError(ErrorCode.INVALID_INVITATION) { join(participant, invite) }
    }

    @Test
    fun `same join key with another invitation conflicts across memberships and invalid input also conflicts`() {
        val owner = member()
        val newcomer = member()
        val firstInvite = issue(owner, create(owner).id)
        val secondInvite = issue(owner, create(owner).id)
        val key = UUID.randomUUID()
        join(newcomer, firstInvite, key)
        assertError(ErrorCode.IDEMPOTENCY_CONFLICT) { join(newcomer, secondInvite, key) }
        assertError(ErrorCode.IDEMPOTENCY_CONFLICT) { invitations.join(newcomer, key, "invalid") }
        assertThat(count("bunch_memberships")).isEqualTo(3)
        // A fresh key can legitimately join the second target.
        assertThat(join(newcomer, secondInvite).outcome).isEqualTo(JoinOutcome.JOINED)
    }

    @Test
    fun `concurrent joins from seven members admit only one eighth member`() {
        val owner = member()
        val bunch = create(owner)
        val invite = issue(owner, bunch.id)
        repeat(6) { join(member(), invite) }
        val contenders = listOf(member(), member())
        val before = count("bunch_join_notifications")
        val results = concurrently(contenders.map { contender -> { join(contender, invite) } })
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat((results.single { it.isFailure }.exceptionOrNull() as BusinessException).errorCode).isEqualTo(ErrorCode.BUNCH_FULL)
        assertThat(count("bunch_memberships")).isEqualTo(8)
        assertThat(count("bunch_join_notifications") - before).isEqualTo(7)
        val outsider = member()
        assertThat(invitations.verify(outsider, invite.code).eligibility).isEqualTo(InviteEligibility.FULL)
    }

    @Test
    fun `same member concurrent joins from nine affiliations admit only one tenth affiliation`() {
        val owner = member()
        val newcomer = member()
        repeat(9) { create(newcomer) }
        val inviteA = issue(owner, create(owner).id)
        val inviteB = issue(owner, create(owner).id)
        val results = concurrently(listOf({ join(newcomer, inviteA) }, { join(newcomer, inviteB) }))
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat((results.single { it.isFailure }.exceptionOrNull() as BusinessException).errorCode).isEqualTo(ErrorCode.MEMBERSHIP_LIMIT_EXCEEDED)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bunch_memberships WHERE member_id = ? AND status = 'ACTIVE'", Int::class.java, newcomer)).isEqualTo(10)
        val failedInvite = if (results[0].isFailure) inviteA else inviteB
        assertThat(invitations.verify(newcomer, failedInvite.code).eligibility).isEqualTo(InviteEligibility.LIMIT_EXCEEDED)
        assertError(ErrorCode.MEMBERSHIP_LIMIT_EXCEEDED) { join(newcomer, failedInvite) }
    }

    @Test
    fun `same join key concurrent requests create one membership and one notification`() {
        val owner = member()
        val newcomer = member()
        val invite = issue(owner, create(owner).id)
        val key = UUID.randomUUID()
        val results = concurrently(List(5) { { join(newcomer, invite, key) } }).map { it.getOrThrow() }
        assertThat(results.toSet()).hasSize(1)
        assertThat(count("bunch_memberships")).isEqualTo(2)
        assertThat(count("bunch_join_notifications")).isEqualTo(1)
    }

    @Test
    fun `real Redis shares invalid attempts across verify and join with bounded expiry`() {
        val visitor = member()
        repeat(5) {
            assertError(ErrorCode.INVALID_INVITATION) { invitations.verify(visitor, "bad") }
            assertError(ErrorCode.INVALID_INVITATION) { invitations.join(visitor, UUID.randomUUID(), "bad") }
        }
        assertError(ErrorCode.RATE_LIMITED) { invitations.verify(visitor, "bad") }
        assertError(ErrorCode.RATE_LIMITED) { invitations.join(visitor, UUID.randomUUID(), "bad") }
        val key = "bunch:invitation:invalid:$visitor"
        assertThat(redis.opsForValue().get(key)).isEqualTo("10")
        assertThat(redis.getExpire(key)).isBetween(1L, 600L)
        val other = member()
        assertError(ErrorCode.INVALID_INVITATION) { invitations.verify(other, "bad") }
        redis.delete(key)
        assertError(ErrorCode.INVALID_INVITATION) { invitations.verify(visitor, "bad") }
    }

    @Test
    fun `real Redis public request budget includes invalid attempts and isolates remote addresses`() {
        val owner = member()
        val invite = issue(owner, create(owner).id)
        repeat(60) { assertError(ErrorCode.INVALID_INVITATION) { invitations.publicPreview("bad", "public-rate-test") } }
        assertError(ErrorCode.RATE_LIMITED) { invitations.publicPreview(invite.code, "public-rate-test") }
        assertThat(invitations.publicPreview(invite.code, "another-public-address").code).isEqualTo(invite.code)
        val publicKeys = redis.keys("bunch:invitation:public:*")
        assertThat(publicKeys).hasSize(2)
        publicKeys.forEach { assertThat(redis.getExpire(it)).isBetween(1L, 60L) }
    }

    private fun expireInvitations() {
        jdbc.update("UPDATE bunch_invitations SET created_at = CURRENT_TIMESTAMP - INTERVAL '8 days', expires_at = CURRENT_TIMESTAMP - INTERVAL '1 day'")
    }
}
