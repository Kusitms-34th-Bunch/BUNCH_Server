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
    val role: MembershipRole,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val status: MembershipStatus = MembershipStatus.ACTIVE,

    @Column(nullable = false)
    val joinedAt: Instant,

    @Column(nullable = false)
    val roleGrantedAt: Instant = joinedAt,

    @Column(nullable = false)
    var lastUsedAt: Instant = joinedAt,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    var joinRequests: Map<String, JoinRequest> = emptyMap()
) {
    fun rememberJoin(
        key: UUID,
        fingerprint: String,
        outcome: JoinOutcome
    ) {
        joinRequests = joinRequests + (key.toString() to JoinRequest(fingerprint, outcome))
    }
}
