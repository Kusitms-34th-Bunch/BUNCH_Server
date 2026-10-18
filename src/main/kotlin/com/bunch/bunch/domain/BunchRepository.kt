package com.bunch.bunch.domain

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface BunchRepository : JpaRepository<Bunch, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Bunch b where b.id = :id")
    fun findLocked(id: Long): Bunch?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findByCreatedByAndCreationRequestKey(
        createdBy: Long,
        creationRequestKey: UUID
    ): Bunch?

    fun existsByName(name: String): Boolean

    // ON CONFLICT preserves the duplicate-name response without aborting the transaction.
    @Modifying
    @Query(
        value = """INSERT INTO bunches(name, introduction, visibility, character_type, created_by,
        creation_request_key, creation_fingerprint, created_at)
        VALUES (:name, :introduction, :visibility, :characterType, :memberId, :key, :fingerprint, :now)
        ON CONFLICT (name) DO NOTHING""",
        nativeQuery = true
    )
    fun insertIfNameAvailable(
        memberId: Long,
        key: UUID,
        fingerprint: String,
        name: String,
        introduction: String,
        visibility: String,
        characterType: String,
        now: Instant
    ): Int
}
