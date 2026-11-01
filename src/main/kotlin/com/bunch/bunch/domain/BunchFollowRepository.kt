package com.bunch.bunch.domain

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface BunchFollowRepository : JpaRepository<BunchFollow, Long> {
    @Modifying
    @Query("""delete from BunchFollow f where
        (f.followerBunchId = :firstId and f.followedBunchId = :secondId) or
        (f.followerBunchId = :secondId and f.followedBunchId = :firstId)""")
    fun deleteBetween(firstId: Long, secondId: Long): Int

    @Query("""select f from BunchFollow f where f.followedBunchId = :targetId
        and f.status = com.bunch.bunch.domain.BunchFollowStatus.APPROVED
        and not exists (select b.id from BunchBlock b where
            (b.blockerBunchId = :viewerId and b.blockedBunchId = f.followerBunchId) or
            (b.blockedBunchId = :viewerId and b.blockerBunchId = f.followerBunchId))""")
    fun visibleFollowers(targetId: Long, viewerId: Long?, pageable: Pageable): Page<BunchFollow>

    @Query("""select f from BunchFollow f where f.followerBunchId = :targetId
        and f.status = com.bunch.bunch.domain.BunchFollowStatus.APPROVED
        and not exists (select b.id from BunchBlock b where
            (b.blockerBunchId = :viewerId and b.blockedBunchId = f.followedBunchId) or
            (b.blockedBunchId = :viewerId and b.blockerBunchId = f.followedBunchId))""")
    fun visibleFollowing(targetId: Long, viewerId: Long?, pageable: Pageable): Page<BunchFollow>

    @Query("""select count(f) from BunchFollow f where f.followedBunchId = :targetId
        and f.status = com.bunch.bunch.domain.BunchFollowStatus.APPROVED
        and not exists (select b.id from BunchBlock b where
            (b.blockerBunchId = :viewerId and b.blockedBunchId = f.followerBunchId) or
            (b.blockedBunchId = :viewerId and b.blockerBunchId = f.followerBunchId))""")
    fun countFollowers(targetId: Long, viewerId: Long?): Long

    @Query("""select count(f) from BunchFollow f where f.followerBunchId = :targetId
        and f.status = com.bunch.bunch.domain.BunchFollowStatus.APPROVED
        and not exists (select b.id from BunchBlock b where
            (b.blockerBunchId = :viewerId and b.blockedBunchId = f.followedBunchId) or
            (b.blockedBunchId = :viewerId and b.blockerBunchId = f.followedBunchId))""")
    fun countFollowing(targetId: Long, viewerId: Long?): Long

    @Query("select b from Bunch b left join fetch b.profileImageAsset where b.id in :ids")
    fun findCardBunches(ids: Collection<Long>): List<Bunch>

    fun findByFollowerBunchIdAndFollowedBunchId(followerBunchId: Long, followedBunchId: Long): BunchFollow?

    @Query("select f.followerBunchId from BunchFollow f where f.id = :id and f.followedBunchId = :followedBunchId")
    fun findRequesterId(id: Long, followedBunchId: Long): Long?

    fun findAllByFollowerBunchIdAndFollowedBunchIdIn(
        followerBunchId: Long,
        followedBunchIds: Collection<Long>
    ): List<BunchFollow>

    fun findAllByFollowedBunchIdAndStatus(
        followedBunchId: Long,
        status: BunchFollowStatus,
        pageable: Pageable
    ): Page<BunchFollow>
}
