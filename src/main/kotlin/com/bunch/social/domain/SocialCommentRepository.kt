package com.bunch.social.domain

import com.bunch.feed.domain.FeedAccessRepository
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface SocialCommentRepository : JpaRepository<SocialComment, Long> {
    fun findByAuthorMemberIdAndActingBunchIdAndBundleIdAndRequestKey(
        authorMemberId: Long,
        actingBunchId: Long,
        bundleId: Long,
        requestKey: UUID
    ): SocialComment?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from SocialComment c where c.id = :id and c.bundleId = :bundleId")
    fun findLocked(bundleId: Long, id: Long): SocialComment?

    // The same change a member makes when deleting one comment (6.2.9), applied to all of them.
    @Modifying(flushAutomatically = true)
    @Query("""update SocialComment c set c.content = '', c.deletedAt = :now
        where c.authorMemberId = :memberId and c.deletedAt is null""")
    fun deleteAllByAuthor(memberId: Long, now: Instant): Int

    @Query("select c from SocialComment c where c.bundleId = :bundleId and c.parentId is null and " + VISIBLE + """
        and (c.createdAt < :at or (c.createdAt = :at and c.id < :afterId))
        order by c.createdAt desc, c.id desc
        """)
    fun roots(
        bundleId: Long,
        currentBunchId: Long?,
        at: Instant,
        afterId: Long,
        pageable: Pageable
    ): List<SocialComment>

    @Query("select c from SocialComment c where c.bundleId = :bundleId and c.parentId = :parentId and " +
        VISIBLE + """
        and (c.createdAt > :at or (c.createdAt = :at and c.id > :afterId))
        order by c.createdAt asc, c.id asc
        """)
    fun replies(
        bundleId: Long,
        parentId: Long,
        currentBunchId: Long?,
        at: Instant,
        afterId: Long,
        pageable: Pageable
    ): List<SocialComment>

    @Query("select c from SocialComment c where c.bundleId = :bundleId and c.id = :id and " + VISIBLE)
    fun visible(bundleId: Long, id: Long, currentBunchId: Long?): SocialComment?

    // F must accept a permitted re-report after the first reporter hide. Never use this for list content.
    @Query("select c from SocialComment c where c.bundleId = :bundleId and c.id = :id and " +
        AUTHOR_VISIBLE + PARENT_VISIBLE + """
        and not exists (select h.id from FeedContentHide h where
            h.targetKind = com.bunch.feed.domain.FeedContentKind.COMMENT and h.targetId = c.id
            and h.reporterBunchId is null)
        """)
    fun reportable(bundleId: Long, id: Long, currentBunchId: Long?): SocialComment?

    @Query("select c.bundleId as bundleId, count(c.id) as total from SocialComment c " +
        "where c.bundleId in :bundleIds and " + VISIBLE + " group by c.bundleId")
    fun counts(bundleIds: Collection<Long>, currentBunchId: Long?): List<BundleCount>

    @Query("select c.parentId as parentId, count(c.id) as total from SocialComment c " +
        "where c.parentId in :parentIds and " + VISIBLE + " group by c.parentId")
    fun replyCounts(parentIds: Collection<Long>, currentBunchId: Long?): List<ReplyCount>

    interface BundleCount {
        val bundleId: Long
        val total: Long
    }

    interface ReplyCount {
        val parentId: Long
        val total: Long
    }

    companion object {
        const val AUTHOR_VISIBLE = """
            (c.actingBunchId is null or exists (select bunch.id from Bunch bunch
                where bunch.id = c.actingBunchId and
            """ + FeedAccessRepository.VISIBLE_BUNCH + ")) "

        const val NOT_HIDDEN = """
            and not exists (select h.id from FeedContentHide h where
                h.targetKind = com.bunch.feed.domain.FeedContentKind.COMMENT and h.targetId = c.id
                and (h.reporterBunchId is null or h.reporterBunchId = :currentBunchId))
        """

        const val PARENT_VISIBLE = """
            and (c.parentId is null or exists (select parent.id from SocialComment parent
                where parent.id = c.parentId
                and (parent.actingBunchId is null or exists (select bunch.id from Bunch bunch
                    where bunch.id = parent.actingBunchId and
            """ + FeedAccessRepository.VISIBLE_BUNCH + """
                ))
                and not exists (select ph.id from FeedContentHide ph where
                    ph.targetKind = com.bunch.feed.domain.FeedContentKind.COMMENT and ph.targetId = parent.id
                    and (ph.reporterBunchId is null or ph.reporterBunchId = :currentBunchId))))
        """

        const val VISIBLE = AUTHOR_VISIBLE + NOT_HIDDEN + PARENT_VISIBLE
    }
}
