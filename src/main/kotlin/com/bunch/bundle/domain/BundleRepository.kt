package com.bunch.bundle.domain

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface BundleRepository : JpaRepository<Bundle, Long> {
    @Query("select bundle.bunchId from Bundle bundle where bundle.id = :id")
    fun findBunchId(@Param("id") id: Long): Long?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select bundle from Bundle bundle where bundle.id = :id")
    fun findLocked(@Param("id") id: Long): Bundle?

    fun findAllByBunchIdAndStatus(
        bunchId: Long,
        status: BundleStatus,
        pageable: Pageable
    ): Page<Bundle>

    fun countByBunchIdAndStatus(
        bunchId: Long,
        status: BundleStatus
    ): Long

    fun findAllByBunchIdOrderByIdAsc(bunchId: Long): List<Bundle>
}
