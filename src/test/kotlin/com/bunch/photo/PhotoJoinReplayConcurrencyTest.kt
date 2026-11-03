package com.bunch.photo

import com.bunch.bunch.domain.BunchMembershipRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@ActiveProfiles("test")
class PhotoJoinReplayConcurrencyTest : PhotoUploadTestSupport() {
    @Autowired
    private lateinit var memberships: BunchMembershipRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `join replay preserves activity committed after its membership was first loaded`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val visitor = member()
        val invitation = issue(ownerId, bunchId)
        val key = UUID.randomUUID()
        join(visitor, invitation, key)
        val executor = Executors.newSingleThreadExecutor()
        var committedActivity: Timestamp? = null
        try {
            TransactionTemplate(transactionManager).execute {
                // Match the replay's first-level cache: membership was read before the bunch lock.
                val stale = memberships.findByMemberIdAndBunchIdAndStatus(visitor, bunchId)!!
                assertThat(stale.lastActivityAt).isNull()
                executor.submit<Any> {
                    uploads.upload(command(bunchId, visitor), image("concurrent-replay"))
                }.get(15, TimeUnit.SECONDS)
                committedActivity = activity(visitor, bunchId)
                assertThat(committedActivity).isNotNull()
                join(visitor, invitation, key)
            }
            assertThat(activity(visitor, bunchId)).isEqualTo(committedActivity)
            assertThat(jdbc.queryForObject(
                "SELECT membership_version FROM bunches WHERE id = ?", Long::class.java, bunchId
            )).isEqualTo(2)
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun activity(memberId: Long, bunchId: Long): Timestamp? = jdbc.queryForObject(
        "SELECT last_activity_at FROM bunch_memberships WHERE member_id = ? AND bunch_id = ? AND status = 'ACTIVE'",
        Timestamp::class.java,
        memberId,
        bunchId
    )
}
