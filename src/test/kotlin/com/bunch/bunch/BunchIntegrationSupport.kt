package com.bunch.bunch

import com.bunch.bunch.domain.*
import com.bunch.bunch.dto.*
import com.bunch.bunch.service.BunchService
import com.bunch.bunch.service.InvitationService
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.member.domain.Member
import com.bunch.member.domain.MemberRepository
import com.bunch.member.domain.SignupStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

abstract class BunchIntegrationSupport {
    @Autowired protected lateinit var bunches: BunchService
    @Autowired protected lateinit var invitations: InvitationService
    @Autowired protected lateinit var members: MemberRepository
    @Autowired protected lateinit var jdbc: JdbcTemplate
    @Autowired protected lateinit var redis: StringRedisTemplate
    private val ownMemberIds = mutableListOf<Long>()

    @BeforeEach
    fun beforeBunchTest() = cleanBunchData()

    @AfterEach
    fun afterBunchTest() {
        // Existing member tests delete every member; no bunch FK may survive this class.
        cleanBunchData()
        ownMemberIds.forEach { jdbc.update("DELETE FROM members WHERE id = ?", it) }
        ownMemberIds.clear()
    }

    private fun cleanBunchData() {
        jdbc.execute("DROP TRIGGER IF EXISTS bunch_test_reject_owner ON bunch_memberships")
        jdbc.execute("DROP TRIGGER IF EXISTS bunch_test_reject_notification ON bunch_join_notifications")
        jdbc.execute("DROP TRIGGER IF EXISTS bunch_test_reject_image ON bunches")
        jdbc.execute("DROP FUNCTION IF EXISTS bunch_test_reject_write()")
        jdbc.update("DELETE FROM bunch_join_notifications")
        jdbc.update("DELETE FROM bunch_invitations")
        jdbc.update("DELETE FROM bunch_memberships")
        jdbc.update("DELETE FROM bunches")
        redis.keys("bunch:invitation:*").takeIf { it.isNotEmpty() }?.let { redis.delete(it) }
    }

    protected fun member(completed: Boolean = true): Long {
        val id = members.saveAndFlush(Member(nickname = uniqueName(), signupStatus =
            if (completed) SignupStatus.COMPLETED else SignupStatus.IN_PROGRESS)).id
        ownMemberIds.add(id)
        return id
    }

    protected fun uniqueName(): String = "B" + UUID.randomUUID().toString().replace("-", "").take(10)
    protected fun request(name: String = uniqueName()) = CreateBunchRequest(name, "함께 기록해요 📷", Visibility.PRIVATE, CharacterType.TYPE_2)
    protected fun create(memberId: Long, name: String = uniqueName()): BunchResponse = bunches.create(memberId, UUID.randomUUID(), request(name))
    protected fun issue(owner: Long, bunchId: Long): InvitationResponse = invitations.issue(owner, bunchId)
    protected fun join(memberId: Long, invitation: InvitationResponse, key: UUID = UUID.randomUUID()) = invitations.join(memberId, key, invitation.url)
    protected fun count(table: String): Long = jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java)!!

    protected fun assertError(code: ErrorCode, block: () -> Any?) {
        val error = catchThrowable { block() }
        assertThat(error).isInstanceOf(BusinessException::class.java)
        assertThat((error as BusinessException).errorCode).isEqualTo(code)
    }

    protected fun rejectWrite(trigger: String, table: String, operation: String, condition: String = "") {
        jdbc.execute("""CREATE FUNCTION bunch_test_reject_write() RETURNS trigger LANGUAGE plpgsql AS
            'BEGIN RAISE EXCEPTION ''injected bunch write failure''; END'""")
        jdbc.execute("CREATE TRIGGER $trigger BEFORE $operation ON $table FOR EACH ROW $condition EXECUTE FUNCTION bunch_test_reject_write()")
    }

    protected fun <T> concurrently(actions: List<() -> T>): List<Result<T>> {
        val executor = Executors.newFixedThreadPool(actions.size)
        val ready = CountDownLatch(actions.size)
        val start = CountDownLatch(1)
        try {
            val futures = actions.map { action -> executor.submit(Callable {
                ready.countDown()
                check(start.await(10, TimeUnit.SECONDS))
                runCatching(action)
            }) }
            check(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}
