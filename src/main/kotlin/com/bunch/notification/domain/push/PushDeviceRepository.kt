package com.bunch.notification.domain.push

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface PushDeviceRepository : JpaRepository<PushDevice, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from PushDevice d where d.installationId = :installationId")
    fun findByInstallationIdLocked(installationId: UUID): PushDevice?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select d from PushDevice d
        where d.installationId = :installationId
           or (d.environment = :environment and d.topic = :topic and d.tokenHash = :tokenHash)
        order by d.id
    """)
    fun findForRegistrationLocked(
        installationId: UUID,
        environment: PushDeviceEnvironment,
        topic: String,
        tokenHash: String
    ): List<PushDevice>

    fun findAllByMemberId(memberId: Long): List<PushDevice>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from PushDevice d where d.memberId = :memberId and d.sessionId = :sessionId order by d.id")
    fun findBySessionLocked(memberId: Long, sessionId: UUID): List<PushDevice>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from PushDevice d where d.id = :id")
    fun findLocked(id: Long): PushDevice?

    @Modifying
    @Query("delete from PushDevice d where d.memberId = :memberId and d.sessionId = :sessionId")
    fun deleteByMemberIdAndSessionId(memberId: Long, sessionId: UUID): Int

    @Modifying(flushAutomatically = true)
    @Query("delete from PushDevice d where d.memberId = :memberId")
    fun deleteByMemberId(memberId: Long): Int

    @Modifying
    @Query("""
        delete from PushDevice d
        where d.id = :id and d.memberId = :memberId and d.tokenHash = :tokenHash
          and d.registeredAt = :registeredAt
    """)
    fun deleteIfCurrent(
        id: Long,
        memberId: Long,
        tokenHash: String,
        registeredAt: Instant
    ): Int
}
