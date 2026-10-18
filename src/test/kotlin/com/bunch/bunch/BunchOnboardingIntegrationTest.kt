package com.bunch.bunch

import com.bunch.bunch.domain.*
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataAccessException
import org.springframework.test.context.ActiveProfiles
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class BunchOnboardingIntegrationTest : BunchIntegrationSupport() {
    @Test
    fun `repeated uncombined Hangul is rejected before name lookup and database insertion`() {
        val user = member()
        val name = "\u1161".repeat(121)
        assertError(ErrorCode.INVALID_INPUT) { bunches.nameAvailability(user, name) }
        assertError(ErrorCode.INVALID_INPUT) { bunches.create(user, java.util.UUID.randomUUID(), request(name)) }
        assertThat(count("bunches")).isZero()
    }

    @Test
    fun `creation atomically stores exactly one initial owner with grant and usage timestamps`() {
        val owner = member()
        val created = create(owner)
        assertThat(count("bunches")).isEqualTo(1)
        assertThat(count("bunch_memberships")).isEqualTo(1)
        val membership = jdbc.queryForMap("SELECT * FROM bunch_memberships WHERE bunch_id = ?", created.id)
        assertThat(membership["member_id"]).isEqualTo(owner)
        assertThat(membership["role"]).isEqualTo("OWNER")
        assertThat(membership["status"]).isEqualTo("ACTIVE")
        assertThat(membership["joined_at"]).isEqualTo(membership["role_granted_at"]).isEqualTo(membership["last_used_at"])
        assertThat(bunches.defaultBunchId(owner)).isEqualTo(created.id)
        assertThat(created.characterType).isEqualTo(CharacterType.TYPE_2)
        assertThat(created.imageUrl).isNull()
    }

    @Test
    fun `owner persistence failure rolls back bunch and permits retry with same request key`() {
        val owner = member()
        val key = UUID.randomUUID()
        val request = request()
        rejectWrite("bunch_test_reject_owner", "bunch_memberships", "INSERT", "WHEN (NEW.role = 'OWNER')")
        assertThatThrownBy { bunches.create(owner, key, request) }.isInstanceOf(DataAccessException::class.java)
        assertThat(count("bunches")).isZero()
        assertThat(count("bunch_memberships")).isZero()
        jdbc.execute("DROP TRIGGER bunch_test_reject_owner ON bunch_memberships")
        bunches.create(owner, key, request)
        assertThat(count("bunches")).isEqualTo(1)
        assertThat(count("bunch_memberships")).isEqualTo(1)
    }

    @Test
    fun `normalized creation replay succeeds at ten affiliations and different input conflicts`() {
        val owner = member()
        val key = UUID.randomUUID()
        val request = request("  가 Test  ").copy(introduction = "  소개 📷\n둘째 줄  ")
        val first = bunches.create(owner, key, request)
        repeat(9) { create(owner) }
        val replay = bunches.create(owner, key, request.copy(name = "가 Test", introduction = "소개 📷\n둘째 줄"))
        assertThat(replay).isEqualTo(first)
        assertError(ErrorCode.IDEMPOTENCY_CONFLICT) { bunches.create(owner, key, request.copy(visibility = Visibility.PUBLIC)) }
        assertError(ErrorCode.IDEMPOTENCY_CONFLICT) { bunches.create(owner, key, request.copy(characterType = CharacterType.TYPE_4)) }
        assertError(ErrorCode.IDEMPOTENCY_CONFLICT) { bunches.create(owner, key, request.copy(introduction = "다른 소개")) }
        assertError(ErrorCode.MEMBERSHIP_LIMIT_EXCEEDED) { create(owner) }
        assertThat(count("bunches")).isEqualTo(10)
    }

    @Test
    fun `database name uniqueness normalizes NFC but distinguishes case and internal spaces`() {
        val owner = member()
        create(owner, "가 Name")
        assertThat(bunches.nameAvailability(owner, "  가 Name  ").available).isFalse()
        assertError(ErrorCode.BUNCH_NAME_TAKEN) { create(owner, "가 Name") }
        create(owner, "가 name")
        create(owner, "가  Name")
        assertThat(count("bunches")).isEqualTo(3)
        assertError(ErrorCode.INVALID_INPUT) { create(owner, "금지!") }
    }

    @Test
    fun `concurrent same name creation returns a domain conflict and leaves no orphan owner`() {
        val owners = listOf(member(), member())
        val name = uniqueName()
        val results = concurrently(owners.map { owner -> { create(owner, name) } })
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat((results.single { it.isFailure }.exceptionOrNull() as BusinessException).errorCode).isEqualTo(ErrorCode.BUNCH_NAME_TAKEN)
        assertThat(count("bunches")).isEqualTo(1)
        assertThat(count("bunch_memberships")).isEqualTo(1)
    }

    @Test
    fun `same member concurrent creation key returns one bunch and one owner`() {
        val owner = member()
        val key = UUID.randomUUID()
        val request = request()
        val results = concurrently(List(4) { { bunches.create(owner, key, request) } }).map { it.getOrThrow() }
        assertThat(results.map { it.id }.toSet()).hasSize(1)
        assertThat(count("bunch_memberships")).isEqualTo(1)
    }

    @Test
    fun `default bunch uses active membership usage then id and falls back when membership is invalidated`() {
        val owner = member()
        val first = create(owner)
        val second = create(owner)
        assertThat(bunches.defaultBunchId(owner)).isEqualTo(second.id)
        bunches.updateLastUsed(owner, first.id)
        assertThat(bunches.defaultBunchId(owner)).isEqualTo(first.id)
        jdbc.update("UPDATE bunch_memberships SET last_used_at = ? WHERE member_id = ?", Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")), owner)
        assertThat(bunches.defaultBunchId(owner)).isEqualTo(second.id)
        jdbc.update("UPDATE bunch_memberships SET status = 'REMOVED' WHERE bunch_id = ?", second.id)
        assertThat(bunches.defaultBunchId(owner)).isEqualTo(first.id)
        jdbc.update("UPDATE bunch_memberships SET status = 'LEFT' WHERE bunch_id = ?", first.id)
        assertThat(bunches.defaultBunchId(owner)).isNull()
        assertError(ErrorCode.FORBIDDEN) { bunches.updateLastUsed(owner, second.id) }
    }

    @Test
    fun `pending and inactive accounts cannot use bunch features even with role user`() {
        val owner = member()
        val pending = member(completed = false)
        val bunch = create(owner)
        val invite = issue(owner, bunch.id)
        assertError(ErrorCode.SIGNUP_INCOMPLETE) { create(pending) }
        assertError(ErrorCode.SIGNUP_INCOMPLETE) { bunches.nameAvailability(pending, "이름") }
        assertError(ErrorCode.SIGNUP_INCOMPLETE) { invitations.issue(pending, bunch.id) }
        assertError(ErrorCode.SIGNUP_INCOMPLETE) { invitations.verify(pending, invite.code) }
        assertError(ErrorCode.SIGNUP_INCOMPLETE) { join(pending, invite) }
        assertError(ErrorCode.SIGNUP_INCOMPLETE) { bunches.updateLastUsed(pending, bunch.id) }
        assertError(ErrorCode.SIGNUP_INCOMPLETE) { bunches.resetProfileImage(pending, bunch.id) }
        assertThat(bunches.defaultBunchId(pending)).isNull()
        jdbc.update("UPDATE members SET account_status = 'SUSPENDED' WHERE id = ?", owner)
        assertError(ErrorCode.UNAUTHORIZED) { create(owner) }
        assertError(ErrorCode.UNAUTHORIZED) { invitations.issue(owner, bunch.id) }
        assertError(ErrorCode.UNAUTHORIZED) { invitations.verify(owner, invite.code) }
        assertError(ErrorCode.UNAUTHORIZED) { join(owner, invite) }
    }
}
