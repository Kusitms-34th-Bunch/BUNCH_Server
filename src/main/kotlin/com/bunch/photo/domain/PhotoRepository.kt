package com.bunch.photo.domain

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.UUID

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

// Photos without a capture date, newest upload first. A photo the user dated has a date, so it is not here.
private const val READY_IN_BUNCH =
    " where p.bunch.id = :bunchId and p.processingStatus = com.bunch.photo.domain.ProcessingStatus.READY"
private const val UNDATED_PHOTOS =
    "select p.id as id, a.url as imageUrl from Photo p join p.imageAsset a" + READY_IN_BUNCH +
        " and p.capturedDate is null"
private const val UNDATED_COUNT =
    "select count(p) from Photo p" + READY_IN_BUNCH + " and p.capturedDate is null"
private const val UNDATED_AFTER = " and p.id < :#{#after.id}"
private const val UNDATED_ORDER = " order by p.id desc"

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

    @Query(UNDATED_PHOTOS + UNDATED_AFTER + UNDATED_ORDER)
    fun findUndatedPage(
        @Param("bunchId") bunchId: Long,
        @Param("after") after: PhotoUndatedCursor,
        limit: Limit
    ): List<PhotoUndatedRow>

    @Query(UNDATED_PHOTOS + UPLOADED_BY + UNDATED_AFTER + UNDATED_ORDER)
    fun findUndatedPageByUploader(
        @Param("bunchId") bunchId: Long,
        @Param("uploaderId") uploaderId: Long,
        @Param("after") after: PhotoUndatedCursor,
        limit: Limit
    ): List<PhotoUndatedRow>

    @Query(UNDATED_COUNT)
    fun countUndated(@Param("bunchId") bunchId: Long): Long

    @Query(UNDATED_COUNT + UPLOADED_BY)
    fun countUndatedByUploader(@Param("bunchId") bunchId: Long, @Param("uploaderId") uploaderId: Long): Long

    // A photo of another bunch or one that is not ready is the same as no photo.
    @Query(
        "select p.id as id, p.capturedDate as capturedDate, a.url as imageUrl " +
            "from Photo p join p.imageAsset a" + READY_IN_BUNCH + " and p.id = :photoId"
    )
    fun findDetail(@Param("bunchId") bunchId: Long, @Param("photoId") photoId: Long): PhotoDetailRow?

    // The badge shows the member as they are now, so the nickname and profile image are read from the member.
    @Query(
        "select m.id as memberId, m.nickname as nickname, a.url as imageUrl from PhotoUploader u " +
            "join u.member m left join m.profileImageAsset a where u.photo.id = :photoId order by u.id asc"
    )
    fun findUploaderProfiles(@Param("photoId") photoId: Long): List<PhotoUploaderProfile>

    // Which of the photos a draft bundle uses, for the delete confirmation (3.3.5, 3.5.2). Only a draft counts: a
    // published bundle keeps its own copy of the photo, so deleting from the photo box does not change it.
    @Query(
        "select distinct bp.photoId from BundlePhoto bp, Bundle b where bp.photoId in :photoIds " +
            "and b.id = bp.bundleId and b.status = com.bunch.bundle.domain.BundleStatus.DRAFT"
    )
    fun findDraftBundlePhotoIds(@Param("photoIds") photoIds: Collection<Long>): List<Long>

    @Query("select p from Photo p where p.id in :ids order by p.id asc")
    fun findAllByIdInOrderByIdAsc(@Param("ids") ids: Collection<Long>): List<Photo>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Photo p where p.id in :ids order by p.id asc")
    fun findAllLockedByIdInOrderByIdAsc(@Param("ids") ids: Collection<Long>): List<Photo>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""select distinct photo from Photo photo
        join PhotoUploader uploader on uploader.photo.id = photo.id
        where photo.bunch.id = :bunchId and uploader.member.id = :memberId
        order by photo.id asc""")
    fun findAllLockedByBunchIdAndUploaderMemberIdOrderByIdAsc(
        @Param("bunchId") bunchId: Long,
        @Param("memberId") memberId: Long
    ): List<Photo>

    // The caller holds the photo locks, so an update by ID is enough. The time is not touched: only the date changes.
    // The photos of one request get the same batch (4.2.7 rule 3).
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update Photo p set p.capturedDate = :date, p.dateSource = com.bunch.photo.domain.DateSource.USER, " +
            "p.dateBatchId = :batchId where p.id in :ids"
    )
    fun setCapturedDate(
        @Param("ids") ids: Collection<Long>,
        @Param("date") date: LocalDate,
        @Param("batchId") batchId: UUID
    ): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update Photo p set p.capturedDate = null, p.capturedTime = null, p.dateBatchId = null, " +
            "p.dateSource = com.bunch.photo.domain.DateSource.UNKNOWN where p.id in :ids"
    )
    fun clearCapturedDate(@Param("ids") ids: Collection<Long>): Int

    // The status guard makes the change atomic and repeatable without loading the photo.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Photo p set p.processingStatus = :to where p.id = :id and p.processingStatus = :from")
    fun transitionStatus(
        @Param("id") id: Long,
        @Param("from") from: ProcessingStatus,
        @Param("to") to: ProcessingStatus
    ): Int
}
