package com.bunch.photo.domain

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

// The photo box list: READY photos with a date, newest date first, then the later time, then the lower ID.
// A photo without a time comes last in its day, so a cursor with a time also reaches the photos without one.
// The cursor always has a value (see PhotoListCursor.FIRST), so each query has one shape and binds no null.
private const val LIST_PHOTOS =
    "select p.id as id, p.capturedDate as capturedDate, p.capturedTime as capturedTime, a.url as imageUrl " +
        "from Photo p join p.imageAsset a " +
        "where p.bunch.id = :bunchId and p.processingStatus = com.bunch.photo.domain.ProcessingStatus.READY " +
        "and p.capturedDate is not null"
private const val UPLOADED_BY =
    " and exists (select 1 from PhotoUploader u where u.photo.id = p.id and u.member.id = :uploaderId)"
private const val AFTER_CURSOR =
    " and (p.capturedDate < :#{#after.date} or (p.capturedDate = :#{#after.date} and (" +
        "(:#{#after.hasTime} = true and (p.capturedTime < :#{#after.timeOrMin} or p.capturedTime is null or " +
        "(p.capturedTime = :#{#after.timeOrMin} and p.id > :#{#after.id}))) or " +
        "(:#{#after.hasTime} = false and p.capturedTime is null and p.id > :#{#after.id}))))"
private const val LIST_ORDER = " order by p.capturedDate desc, p.capturedTime desc nulls last, p.id asc"

interface PhotoRepository : JpaRepository<Photo, Long> {
    fun findByBunchIdAndFileHash(bunchId: Long, fileHash: String): Photo?

    fun countByBunchId(bunchId: Long): Long

    @Query(LIST_PHOTOS + AFTER_CURSOR + LIST_ORDER)
    fun findListPage(
        @Param("bunchId") bunchId: Long,
        @Param("after") after: PhotoListCursor,
        limit: Limit
    ): List<PhotoListRow>

    @Query(LIST_PHOTOS + UPLOADED_BY + AFTER_CURSOR + LIST_ORDER)
    fun findListPageByUploader(
        @Param("bunchId") bunchId: Long,
        @Param("uploaderId") uploaderId: Long,
        @Param("after") after: PhotoListCursor,
        limit: Limit
    ): List<PhotoListRow>

    // IDs only, in the order they uploaded: the list shows the uploader badges without loading any member.
    @Query(
        "select u.photo.id as photoId, u.member.id as memberId from PhotoUploader u " +
            "where u.photo.id in :photoIds order by u.photo.id asc, u.id asc"
    )
    fun findUploaderIdsByPhotoIds(@Param("photoIds") photoIds: Collection<Long>): List<PhotoUploaderIds>

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
