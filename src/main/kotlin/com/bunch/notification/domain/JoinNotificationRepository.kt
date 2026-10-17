package com.bunch.notification.domain

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

@Repository
class JoinNotificationRepository(private val jdbc: JdbcTemplate) {
    fun notifyExistingMembers(bunchId: Long, joinedMembershipId: Long, joinedMemberId: Long, now: Instant) {
        jdbc.update(
            """INSERT INTO bunch_join_notifications(bunch_id, joined_membership_id, recipient_member_id, joined_member_id, created_at)
                SELECT bunch_id, ?, member_id, ?, ? FROM bunch_memberships
                WHERE bunch_id = ? AND status = 'ACTIVE' AND member_id <> ?""",
            joinedMembershipId, joinedMemberId, Timestamp.from(now), bunchId, joinedMemberId
        )
    }
}
