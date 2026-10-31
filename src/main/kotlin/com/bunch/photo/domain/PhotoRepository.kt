package com.bunch.photo.domain

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface PhotoRepository : JpaRepository<Photo, Long> {
    fun findByBunchIdAndFileHash(bunchId: Long, fileHash: String): Photo?

    fun countByBunchId(bunchId: Long): Long

    @Query("select p from Photo p where p.id in :ids order by p.id asc")
    fun findAllByIdInOrderByIdAsc(@Param("ids") ids: Collection<Long>): List<Photo>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Photo p where p.id in :ids order by p.id asc")
    fun findAllLockedByIdInOrderByIdAsc(@Param("ids") ids: Collection<Long>): List<Photo>

    // The status guard makes the change atomic and repeatable without loading the photo.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Photo p set p.processingStatus = :to where p.id = :id and p.processingStatus = :from")
    fun transitionStatus(
        @Param("id") id: Long,
        @Param("from") from: ProcessingStatus,
        @Param("to") to: ProcessingStatus
    ): Int
}
