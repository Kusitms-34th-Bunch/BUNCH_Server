package com.bunch.bunch.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "bunch_follows")
class BunchFollow(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val followerBunchId: Long,

    @Column(nullable = false)
    val followedBunchId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: BunchFollowStatus,

    @Column(nullable = false)
    val createdByMemberId: Long,

    var approvedByMemberId: Long? = null,

    @Column(nullable = false)
    val createdAt: Instant,

    var approvedAt: Instant? = null
) {
    fun approve(memberId: Long, now: Instant) {
        status = BunchFollowStatus.APPROVED
        approvedByMemberId = memberId
        approvedAt = now
    }
}
