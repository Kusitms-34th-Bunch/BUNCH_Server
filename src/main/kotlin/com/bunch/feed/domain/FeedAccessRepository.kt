package com.bunch.feed.domain

import com.bunch.bundle.domain.Bundle
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository

interface FeedAccessRepository : Repository<Bundle, Long> {
    @Query("select bundle from Bundle bundle, Bunch bunch where bunch.id = bundle.bunchId " +
        "and bundle.id = :bundleId and " + PUBLISHED_ACCESS)
    fun findVisibleBundle(currentBunchId: Long?, bundleId: Long): Bundle?

    @Query("select bundle from Bundle bundle, Bunch bunch where bunch.id = bundle.bunchId " +
        "and bundle.id = :bundleId and " + PUBLISHED_VISIBILITY + """
        and not exists (select hide.id from FeedContentHide hide where
            hide.targetKind = com.bunch.feed.domain.FeedContentKind.BUNDLE and hide.targetId = bundle.id
            and hide.reporterBunchId is null)
        """)
    fun findReportableBundle(currentBunchId: Long, bundleId: Long): Bundle?

    @Query("select bundle from Bundle bundle, Bunch bunch where bunch.id = bundle.bunchId " +
        "and bunch.id = :bunchId and " + PUBLISHED_ACCESS + " order by bundle.publishedAt desc, bundle.id desc")
    fun findPublishedBundles(currentBunchId: Long?, bunchId: Long, pageable: Pageable): Page<Bundle>

    @Query("select bundle.id from Bundle bundle, Bunch bunch where bunch.id = bundle.bunchId " +
        "and bundle.id in :bundleIds and " + PUBLISHED_ACCESS + " and (:forFeed = false or " + FEED_EXCLUSIONS + ")")
    fun visibleBundleIds(
        memberId: Long,
        currentBunchId: Long?,
        bundleIds: Collection<Long>,
        forFeed: Boolean
    ): Set<Long>

    @Query("select bunch.id from Bunch bunch where bunch.id in :bunchIds and " + VISIBLE_BUNCH)
    fun visibleBunchIds(currentBunchId: Long?, bunchIds: Collection<Long>): Set<Long>

    companion object {
        // Shared JPQL predicates for paginated feed queries. Aliases and parameters are part of feed-api.md.
        const val VISIBLE_BUNCH = """
            not exists (select block.id from BunchBlock block where
                (block.blockerBunchId = :currentBunchId and block.blockedBunchId = bunch.id) or
                (block.blockedBunchId = :currentBunchId and block.blockerBunchId = bunch.id))
            and not exists (select report.id from BunchProfileReport report where
                report.reporterBunchId = :currentBunchId and report.targetBunchId = bunch.id)
            and not exists (select hide.id from FeedContentHide hide where
                hide.targetKind = com.bunch.feed.domain.FeedContentKind.BUNCH and hide.targetId = bunch.id
                and (hide.reporterBunchId is null or hide.reporterBunchId = :currentBunchId))
        """

        const val PUBLISHED_VISIBILITY = VISIBLE_BUNCH + """
            and bundle.status = com.bunch.bundle.domain.BundleStatus.PUBLISHED
            and (bunch.id = :currentBunchId or (
                bundle.visibility = com.bunch.bunch.domain.Visibility.PUBLIC and (
                    bunch.visibility = com.bunch.bunch.domain.Visibility.PUBLIC or
                    exists (select follow.id from BunchFollow follow where
                        follow.followerBunchId = :currentBunchId and follow.followedBunchId = bunch.id
                        and follow.status = com.bunch.bunch.domain.BunchFollowStatus.APPROVED))))
        """

        const val PUBLISHED_ACCESS = PUBLISHED_VISIBILITY + """
            and not exists (select hide.id from FeedContentHide hide where
                hide.targetKind = com.bunch.feed.domain.FeedContentKind.BUNDLE and hide.targetId = bundle.id
                and (hide.reporterBunchId is null or hide.reporterBunchId = :currentBunchId))
        """

        const val FEED_EXCLUSIONS = """
            not exists (select membership.id from BunchMembership membership where
                membership.member.id = :memberId and membership.bunch.id = bunch.id
                and membership.status = com.bunch.bunch.domain.MembershipStatus.ACTIVE)
        """
    }
}
