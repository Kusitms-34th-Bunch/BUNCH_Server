package com.bunch.photo.domain

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface PhotoUploaderRepository : JpaRepository<PhotoUploader, Long> {
    fun existsByPhotoIdAndMemberId(photoId: Long, memberId: Long): Boolean

    fun countByMemberIdAndUploadedAtGreaterThanEqual(memberId: Long, uploadedAt: Instant): Long

    fun countByMemberIdAndUploadBatchId(memberId: Long, uploadBatchId: UUID): Long

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select uploader from PhotoUploader uploader " +
            "where uploader.photo.id = :photoId order by uploader.id asc"
    )
    fun findAllLockedByPhotoIdOrderByIdAsc(@Param("photoId") photoId: Long): List<PhotoUploader>

    @Query(
        "select uploader from PhotoUploader uploader " +
            "join fetch uploader.member " +
            "where uploader.photo.id in :photoIds " +
            "order by uploader.photo.id asc, uploader.id asc"
    )
    fun findAllByPhotoIdInOrderByPhotoIdAndIdAsc(
        @Param("photoIds") photoIds: Collection<Long>
    ): List<PhotoUploader>
}
