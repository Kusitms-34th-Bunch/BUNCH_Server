package com.bunch.photo.domain

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface PhotoRepository : JpaRepository<Photo, Long> {
    @Query("select p from Photo p where p.id in :ids order by p.id asc")
    fun findAllByIdInOrderByIdAsc(@Param("ids") ids: Collection<Long>): List<Photo>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Photo p where p.id in :ids order by p.id asc")
    fun findAllLockedByIdInOrderByIdAsc(@Param("ids") ids: Collection<Long>): List<Photo>
}
