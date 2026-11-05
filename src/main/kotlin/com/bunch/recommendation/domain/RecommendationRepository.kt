package com.bunch.recommendation.domain

import com.bunch.photo.domain.Photo
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import java.time.Instant

// The READY photos with a date and one row for each of their uploaders. This is every photo the bunch shows by date.
private const val DATED_ROWS =
    "select p.id as photoId, p.capturedDate as capturedDate, p.capturedTime as capturedTime, " +
        "u.member.id as memberId, u.uploadBatchId as uploadBatchId " +
        "from PhotoUploader u join u.photo p " +
        "where p.bunch.id = :bunchId and p.processingStatus = com.bunch.photo.domain.ProcessingStatus.READY " +
        "and p.capturedDate is not null "

private const val ROW_ORDER = "order by p.id asc, u.id asc"

// The photos a recommended bundle can be made of: the dated photos that no bundle uses. A bundle photo is only read
// here. Draft and published bundles hide their photos the same way, and a deleted draft frees them again.
private const val MATERIAL_ROWS =
    DATED_ROWS + "and not exists (select 1 from BundlePhoto b where b.photoId = p.id) " + ROW_ORDER

/**
 * Everything the recommended bundles read and write in one place. A candidate is saved with the entity manager, as
 * the photo box does for its own entities, so the other tables do not need a repository of their own.
 */
interface RecommendationRepository : Repository<Photo, Long> {
    @Query(MATERIAL_ROWS)
    fun findMaterialRows(@Param("bunchId") bunchId: Long): List<MaterialPhotoRow>

    @Query(DATED_ROWS + ROW_ORDER)
    fun findDatedRows(@Param("bunchId") bunchId: Long): List<MaterialPhotoRow>

    @Query("select p.id as photoId, p.createdAt as createdAt from Photo p where p.id in :photoIds")
    fun findAddedAt(@Param("photoIds") photoIds: Collection<Long>): List<PhotoAddedRow>

    @Query("select m.id as memberId, m.nickname as nickname from Member m where m.id in :memberIds")
    fun findNicknames(@Param("memberIds") memberIds: Collection<Long>): List<MemberNicknameRow>

    @Query("select c from BundleCandidate c where c.bunchId = :bunchId")
    fun findCandidates(@Param("bunchId") bunchId: Long): List<BundleCandidate>

    @Query("select p from CandidatePhoto p where p.candidateId in :candidateIds")
    fun findCandidatePhotos(@Param("candidateIds") candidateIds: Collection<Long>): List<CandidatePhoto>

    // The photos of a deleted candidate are deleted with it by the database.
    @Modifying
    @Query("delete from BundleCandidate c where c.id in :candidateIds")
    fun deleteCandidates(@Param("candidateIds") candidateIds: Collection<Long>)

    @Modifying
    @Query("delete from CandidatePhoto p where p.candidateId in :candidateIds")
    fun deleteCandidatePhotos(@Param("candidateIds") candidateIds: Collection<Long>)

    /** When a photo was added to the photo box (`photos.created_at`). */
    interface PhotoAddedRow {
        val photoId: Long
        val createdAt: Instant
    }

    /** The nickname of a member, empty when the member has not set one. */
    interface MemberNicknameRow {
        val memberId: Long
        val nickname: String?
    }
}
