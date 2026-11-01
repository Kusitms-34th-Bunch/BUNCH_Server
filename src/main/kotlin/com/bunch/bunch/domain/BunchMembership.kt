package com.bunch.bunch.domain

import com.bunch.member.domain.Member
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "bunch_memberships")
class BunchMembership(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bunch_id", nullable = false)
    val bunch: Bunch,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    val member: Member,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var role: MembershipRole,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: MembershipStatus = MembershipStatus.ACTIVE,

    @Column(nullable = false)
    val joinedAt: Instant,

    @Column(nullable = false)
    var roleGrantedAt: Instant = joinedAt,

    @Column(nullable = false)
    var lastUsedAt: Instant = joinedAt,

    @Column
    var endedAt: Instant? = null,

    @Column
    var roleGrantedBy: Long? = null,

    @Column
    var lastActivityAt: Instant? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    var joinRequests: Map<String, JoinRequest> = emptyMap()
) {
    fun changeRole(
        newRole: MembershipRole,
        grantorMemberId: Long,
        now: Instant
    ) {
        role = newRole
        roleGrantedAt = now
        roleGrantedBy = grantorMemberId
    }

    fun recordActivity(now: Instant = Instant.now()) {
        if (lastActivityAt == null || now > lastActivityAt) {
            lastActivityAt = now
        }
    }

    fun rememberJoin(
        key: UUID,
        fingerprint: String,
        outcome: JoinOutcome
    ) {
        joinRequests = joinRequests + (key.toString() to JoinRequest(fingerprint, outcome))
    }

    fun changeRole(
        newRole: MembershipRole,
        grantorMemberId: Long,
        now: Instant
    ) {
        role = newRole
        roleGrantedAt = now
        roleGrantedBy = grantorMemberId
    }

    fun end(newStatus: MembershipStatus, endedAt: Instant) {
        check(newStatus == MembershipStatus.LEFT || newStatus == MembershipStatus.REMOVED)
        check(status == MembershipStatus.ACTIVE)
        status = newStatus
        this.endedAt = endedAt
    }
}
