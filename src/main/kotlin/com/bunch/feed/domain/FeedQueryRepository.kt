package com.bunch.feed.domain

import com.bunch.bundle.domain.Bundle
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import java.time.Instant

interface FeedQueryRepository : Repository<Bundle, Long> {
    @Query(PAGE_BASE + """
        and bundle.bunchId in :followingBunchIds and bundle.publishedAt >= :followingSince
        order by bundle.publishedAt desc, bundle.id desc
    """)
    fun following(
        memberId: Long,
        currentBunchId: Long?,
        anchorTime: Instant,
        followingSince: Instant,
        followingBunchIds: Collection<Long>,
        lastTime: Instant?,
        lastId: Long?,
        hasLast: Boolean,
        pageable: Pageable
    ): List<Bundle>

    @Query(PAGE_BASE + """
        and bunch.visibility = com.bunch.bunch.domain.Visibility.PUBLIC
        and not (bundle.bunchId in :followingBunchIds and bundle.publishedAt >= :followingSince)
        order by bundle.publishedAt desc, bundle.id desc
    """)
    fun publicBundles(
        memberId: Long,
        currentBunchId: Long?,
        anchorTime: Instant,
        followingSince: Instant,
        followingBunchIds: Collection<Long>,
        lastTime: Instant?,
        lastId: Long?,
        hasLast: Boolean,
        pageable: Pageable
    ): List<Bundle>

    @Query("""select follow.id as id, follow.followedBunchId as followedBunchId,
        follow.status as status, follow.approvedAt as approvedAt from BunchFollow follow
        where follow.followerBunchId = :currentBunchId
        and follow.status = com.bunch.bunch.domain.BunchFollowStatus.APPROVED
        order by follow.id
    """)
    fun approvedFollowing(currentBunchId: Long): List<FollowingContext>

    @Query("""select membership.id as id, membership.bunch.id as bunchId,
        membership.lastUsedAt as lastUsedAt from BunchMembership membership
        where membership.member.id = :memberId
        and membership.status = com.bunch.bunch.domain.MembershipStatus.ACTIVE
        order by membership.id
    """)
    fun membershipContext(memberId: Long): List<MembershipContext>

    interface MembershipContext {
        val id: Long
        val bunchId: Long
        val lastUsedAt: Instant
    }

    interface FollowingContext {
        val id: Long
        val followedBunchId: Long
        val status: com.bunch.bunch.domain.BunchFollowStatus
        val approvedAt: Instant?
    }

    companion object {
        const val PAGE_BASE = "select bundle from Bundle bundle, Bunch bunch " +
            "where bunch.id = bundle.bunchId and " + FeedAccessRepository.PUBLISHED_ACCESS +
            " and " + FeedAccessRepository.FEED_EXCLUSIONS + """
            and bundle.visibility = com.bunch.bunch.domain.Visibility.PUBLIC
            and bundle.publishedAt <= :anchorTime
            and (:hasLast = false or bundle.publishedAt < :lastTime or
                (bundle.publishedAt = :lastTime and bundle.id < :lastId))
            """
    }
}
