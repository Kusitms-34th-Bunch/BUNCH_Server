package com.bunch.image.domain

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ImageAssetRepository : JpaRepository<ImageAsset, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select asset from ImageAsset asset where asset.id = :id")
    fun findLockedById(@Param("id") id: Long): ImageAsset?

    fun existsByPublicId(publicId: String): Boolean
}
