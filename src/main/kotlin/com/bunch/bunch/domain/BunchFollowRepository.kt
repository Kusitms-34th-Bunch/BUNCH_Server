package com.bunch.bunch.domain

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface BunchFollowRepository : JpaRepository<BunchFollow, Long> {
    @Query("select b from Bunch b left join fetch b.profileImageAsset where b.id in :ids")
    fun findCardBunches(ids: Collection<Long>): List<Bunch>

    fun findByFollowerBunchIdAndFollowedBunchId(followerBunchId: Long, followedBunchId: Long): BunchFollow?

    @Query("select f.followerBunchId from BunchFollow f where f.id = :id and f.followedBunchId = :followedBunchId")
    fun findRequesterId(id: Long, followedBunchId: Long): Long?

    fun findAllByFollowerBunchIdAndFollowedBunchIdIn(
        followerBunchId: Long,
        followedBunchIds: Collection<Long>
    ): List<BunchFollow>

    fun findAllByFollowerBunchIdAndStatus(
        followerBunchId: Long,
        status: BunchFollowStatus,
        pageable: Pageable
    ): Page<BunchFollow>

    fun findAllByFollowedBunchIdAndStatus(
        followedBunchId: Long,
        status: BunchFollowStatus,
        pageable: Pageable
    ): Page<BunchFollow>
}
