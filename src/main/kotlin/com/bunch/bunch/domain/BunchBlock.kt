package com.bunch.bunch.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "bunch_blocks")
class BunchBlock(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val blockerBunchId: Long,

    @Column(nullable = false)
    val blockedBunchId: Long,

    @Column(nullable = false)
    val createdByMemberId: Long,

    @Column(nullable = false)
    val createdAt: Instant
)
