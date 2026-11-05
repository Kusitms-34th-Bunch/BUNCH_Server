package com.bunch.bunch

import com.bunch.bunch.service.BunchPublicService
import com.bunch.global.exception.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataAccessException
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class BunchVersionIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var content: BunchPublicService

    @Test
    fun `creation and each new join increment membership version once but replay and reads do not`() {
        val owner = member()
        val key = UUID.randomUUID()
        val request = request()
        val bunch = bunches.create(owner, key, request)
        assertThat(version(bunch.id)).isEqualTo(1)
        bunches.create(owner, key, request)
        val invite = issue(owner, bunch.id)
        val visitor = member()
        val joinKey = UUID.randomUUID()
        join(visitor, invite, joinKey)
        assertThat(version(bunch.id)).isEqualTo(2)
        join(visitor, invite, joinKey)
        join(visitor, invite)
        content.members(visitor, bunch.id)
        bunches.updateLastUsed(visitor, bunch.id)
        assertThat(version(bunch.id)).isEqualTo(2)
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM bunch_memberships WHERE last_activity_at IS NOT NULL",
            Long::class.java
        )).isZero()
        assertThat(jdbc.queryForObject(
            "SELECT profile_version FROM bunches WHERE id = ?", Long::class.java, bunch.id
        )).isZero()
    }

    @Test
    fun `concurrent distinct joins serialize version increments without lost updates`() {
        val owner = member()
        val bunch = create(owner)
        val invite = issue(owner, bunch.id)
        val visitors = List(4) { member() }
        concurrently(visitors.map { visitor -> { join(visitor, invite) } }).forEach { it.getOrThrow() }
        assertThat(version(bunch.id)).isEqualTo(5)
        assertThat(count("bunch_memberships WHERE bunch_id = ${bunch.id} AND role = 'OWNER' AND status = 'ACTIVE'"))
            .isEqualTo(1)
    }

    @Test
    fun `failed join rolls back membership version with membership and notification`() {
        val owner = member()
        val visitor = member()
        val bunch = create(owner)
        val invite = issue(owner, bunch.id)
        rejectWrite("bunch_test_reject_notification", "notifications", "INSERT")
        assertThatThrownBy { join(visitor, invite) }.isInstanceOf(DataAccessException::class.java)
        assertThat(version(bunch.id)).isEqualTo(1)
        assertThat(count("bunch_memberships")).isEqualTo(1)
    }

    @Test
    fun `co owners cannot change the bunch image and unchanged reset does not increment profile version`() {
        val owner = member()
        val bunch = create(owner)
        val coOwner = member()
        join(coOwner, issue(owner, bunch.id))
        jdbc.update("UPDATE bunch_memberships SET role = 'CO_OWNER' WHERE member_id = ?", coOwner)
        assertError(ErrorCode.FORBIDDEN) { bunches.resetProfileImage(coOwner, bunch.id) }
        bunches.resetProfileImage(owner, bunch.id)
        assertThat(jdbc.queryForObject(
            "SELECT profile_version FROM bunches WHERE id = ?", Long::class.java, bunch.id
        )).isZero()
    }

    private fun version(bunchId: Long): Long = jdbc.queryForObject(
        "SELECT membership_version FROM bunches WHERE id = ?", Long::class.java, bunchId
    )!!
}
