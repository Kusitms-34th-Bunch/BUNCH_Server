package com.bunch.bundle.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface BundleParticipantRepository : JpaRepository<BundleParticipant, Long> {
    fun findAllByBundleIdOrderByJoinedAtAscIdAsc(bundleId: Long): List<BundleParticipant>

    fun findAllByBundleIdInOrderByBundleIdAscJoinedAtAscIdAsc(bundleIds: Collection<Long>): List<BundleParticipant>

    fun findByBundleIdAndMemberId(bundleId: Long, memberId: Long): BundleParticipant?

    @Query(
        value = """
            SELECT participant.id AS "participantId", participant.bundle_id AS "bundleId"
            FROM bundle_participants participant
            JOIN bundles bundle ON bundle.id = participant.bundle_id
            JOIN bunch_memberships membership
                ON membership.bunch_id = bundle.bunch_id
                AND membership.member_id = participant.member_id
                AND membership.status = 'ACTIVE'
            JOIN members member ON member.id = participant.member_id
            WHERE bundle.status = 'DRAFT'
                AND member.account_status = 'ACTIVE'
                AND participant.reminders_cancelled_at IS NULL
                AND (
                    participant.joined_at <= :due24At
                    AND participant.reminder24_notified_at IS NULL
                    OR participant.joined_at <= :due72At
                    AND participant.reminder72_notified_at IS NULL
                )
            ORDER BY participant.joined_at, participant.id, participant.bundle_id
            LIMIT 100
        """,
        nativeQuery = true
    )
    fun findDueReminderCandidates(
        due24At: Instant,
        due72At: Instant
    ): List<DueReminderCandidate>

    interface DueReminderCandidate {
        val participantId: Long
        val bundleId: Long
    }
}
