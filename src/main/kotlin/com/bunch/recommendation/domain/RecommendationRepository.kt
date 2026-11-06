package com.bunch.recommendation.domain

import com.bunch.photo.domain.Photo
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

// The READY photos with a date and one row for each of their uploaders. This is every photo the bunch shows by date.
private const val DATED_ROWS =
    "select p.id as photoId, p.capturedDate as capturedDate, p.capturedTime as capturedTime, " +
        "u.member.id as memberId, u.uploadBatchId as uploadBatchId, f.fingerprint as imageFingerprint " +
        "from PhotoUploader u join u.photo p left join PhotoImageFingerprint f on f.photoId = p.id " +
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

    // One candidate of the bunch, or none when it was deleted or belongs to another bunch.
    @Query("select c from BundleCandidate c where c.id = :id and c.bunchId = :bunchId")
    fun findCandidate(@Param("bunchId") bunchId: Long, @Param("id") id: Long): BundleCandidate?

    // The photos of the cards in the order they are shown in. A photo that was deleted is already gone from
    // candidate_photos, and a photo whose date was cleared is left out here until the next calculation.
    @Query(
        "select cp.candidateId as candidateId, p.id as photoId, a.url as imageUrl, p.capturedDate as capturedDate, " +
            "p.capturedTime as capturedTime from CandidatePhoto cp join Photo p on p.id = cp.photoId " +
            "join p.imageAsset a where cp.candidateId in :candidateIds and cp.position is not null " +
            "and p.capturedDate is not null and p.processingStatus = com.bunch.photo.domain.ProcessingStatus.READY " +
            "order by cp.candidateId asc, cp.position asc"
    )
    fun findCardPhotoRows(@Param("candidateIds") candidateIds: Collection<Long>): List<CardPhotoRow>

    // The badge shows the member as they are now, so the nickname and profile image are read from the member.
    @Query(
        "select u.photo.id as photoId, m.id as memberId, m.nickname as nickname, a.url as imageUrl " +
            "from PhotoUploader u join u.member m left join m.profileImageAsset a " +
            "where u.photo.id in :photoIds order by u.photo.id asc, u.id asc"
    )
    fun findUploaderProfiles(@Param("photoIds") photoIds: Collection<Long>): List<UploaderProfileRow>

    // The order of the list (4.2.9 rule 6): the bundle whose last photo was added most recently first, then the one
    // with the most members who uploaded the photos of its card, then the one with the most photos on its card. These
    // are the numbers the card shows. The candidate number comes last, so the order is the same on every call.
    @Query(
        value = """SELECT c.* FROM bundle_candidates c WHERE c.bunch_id = :bunchId
        ORDER BY c.last_photo_added_at DESC,
            (SELECT count(DISTINCT u.member_id) FROM candidate_photos p
                JOIN photo_uploaders u ON u.photo_id = p.photo_id
                WHERE p.candidate_id = c.id AND p.position IS NOT NULL) DESC,
            (SELECT count(*) FROM candidate_photos p
                WHERE p.candidate_id = c.id AND p.position IS NOT NULL) DESC,
            c.id""",
        nativeQuery = true
    )
    fun findOrderedCandidates(@Param("bunchId") bunchId: Long): List<BundleCandidate>

    // Deletes the candidates whose last photo was added before the cutoff and returns how many. A bunch with noted
    // dates is left, because its calculation is waiting and may raise the time of the last photo added.
    @Modifying
    @Query(
        value = """DELETE FROM bundle_candidates c WHERE c.last_photo_added_at < :cutoff
        AND NOT EXISTS (SELECT 1 FROM recommendation_dirty_dates d WHERE d.bunch_id = c.bunch_id)""",
        nativeQuery = true
    )
    fun deleteExpiredCandidates(@Param("cutoff") cutoff: Instant): Int

    // A date that changed again only gets a newer time, so a bunch has one row for each changed date.
    @Modifying
    @Query(
        value = """INSERT INTO recommendation_dirty_dates (bunch_id, captured_date, changed_at)
        VALUES (:bunchId, :capturedDate, :changedAt)
        ON CONFLICT (bunch_id, captured_date) DO UPDATE SET changed_at = EXCLUDED.changed_at""",
        nativeQuery = true
    )
    fun markChanged(
        @Param("bunchId") bunchId: Long,
        @Param("capturedDate") capturedDate: LocalDate,
        @Param("changedAt") changedAt: Instant
    )

    @Query(
        value = """SELECT captured_date AS capturedDate, changed_at AS changedAt
        FROM recommendation_dirty_dates WHERE bunch_id = :bunchId""",
        nativeQuery = true
    )
    fun findChangedDates(@Param("bunchId") bunchId: Long): List<ChangedDateRow>

    // The bunches whose latest change is not newer than the cutoff, the ones waiting the longest first.
    @Query(
        value = """SELECT bunch_id FROM recommendation_dirty_dates
        GROUP BY bunch_id HAVING max(changed_at) <= :cutoff
        ORDER BY min(changed_at), bunch_id LIMIT :limit""",
        nativeQuery = true
    )
    fun findQuietBunchIds(@Param("cutoff") cutoff: Instant, @Param("limit") limit: Int): List<Long>

    @Modifying
    @Query(value = "DELETE FROM recommendation_dirty_dates WHERE bunch_id = :bunchId", nativeQuery = true)
    fun deleteChangedDates(@Param("bunchId") bunchId: Long)

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

    /** A photo of a card with the image and the date it is shown with. */
    interface CardPhotoRow {
        val candidateId: Long
        val photoId: Long
        val imageUrl: String
        val capturedDate: LocalDate
        val capturedTime: LocalTime?
    }

    /** A member who uploaded a photo, as the badge shows them. */
    interface UploaderProfileRow {
        val photoId: Long
        val memberId: Long
        val nickname: String?
        val imageUrl: String?
    }

    /** A date whose photos changed and when they changed last (4.2.9 rule 2). */
    interface ChangedDateRow {
        val capturedDate: LocalDate
        val changedAt: Instant
    }
}
