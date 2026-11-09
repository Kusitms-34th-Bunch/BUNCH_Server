package com.bunch.bunch

import com.bunch.bunch.service.BunchListService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit

@SpringBootTest
@ActiveProfiles("test")
class BunchCurrentContextIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var list: BunchListService

    @Test
    fun `last-used advances beyond a microsecond tie and list keeps database order`() {
        val owner = member()
        val first = create(owner).id
        val second = create(owner).id
        val tie = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS)

        jdbc.update(
            "UPDATE bunch_memberships SET last_used_at = ? WHERE member_id = ?",
            Timestamp.from(tie),
            owner
        )
        bunches.updateLastUsed(owner, first)

        val firstLastUsed = lastUsedAt(owner, first)
        val secondLastUsed = lastUsedAt(owner, second)
        assertThat(firstLastUsed).isEqualTo(tie.plusNanos(1_000))
        assertThat(firstLastUsed).isAfter(secondLastUsed)
        assertThat(list.list(owner).activeBunches.map { it.bunch.id })
            .containsExactly(first, second)
    }

    private fun lastUsedAt(memberId: Long, bunchId: Long): Instant =
        jdbc.queryForObject(
            "SELECT last_used_at FROM bunch_memberships WHERE member_id = ? AND bunch_id = ?",
            Timestamp::class.java,
            memberId,
            bunchId
        )!!.toInstant().truncatedTo(ChronoUnit.MICROS)
}
