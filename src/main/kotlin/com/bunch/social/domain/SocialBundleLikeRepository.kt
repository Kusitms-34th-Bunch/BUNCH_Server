package com.bunch.social.domain

import com.bunch.feed.domain.FeedAccessRepository
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface SocialBundleLikeRepository : JpaRepository<SocialBundleLike, Long> {
    fun findByBunchIdAndBundleId(bunchId: Long, bundleId: Long): SocialBundleLike?

    @Query(
        """select reaction.bundleId as bundleId, count(reaction) as likeCount,
            max(case when reaction.bunchId = :currentBunchId then 1 else 0 end) as currentBunchLikes
            from SocialBundleLike reaction, Bunch bunch
            where reaction.bunchId = bunch.id and reaction.active = true
                and reaction.bundleId in :bundleIds and """ + FeedAccessRepository.VISIBLE_BUNCH + """
            group by reaction.bundleId
        """
    )
    fun counts(currentBunchId: Long?, bundleIds: Collection<Long>): List<SocialLikeCount>
}
