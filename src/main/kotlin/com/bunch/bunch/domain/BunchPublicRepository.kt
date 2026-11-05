package com.bunch.bunch.domain

import com.bunch.bundle.domain.Bundle
import com.bunch.bunch.dto.BunchPublicBundleCover
import com.bunch.bunch.dto.BunchPublicBundlePhoto
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param

interface BunchPublicRepository : Repository<Bundle, Long> {
    @Query(
        """
        select bundle from Bundle bundle
        where bundle.bunchId = :bunchId
          and bundle.status = com.bunch.bundle.domain.BundleStatus.PUBLISHED
          and (:includePrivate = true or bundle.visibility = com.bunch.bunch.domain.Visibility.PUBLIC)
        order by bundle.publishedAt desc, bundle.id desc
        """
    )
    fun findPublishedBundles(
        @Param("bunchId") bunchId: Long,
        @Param("includePrivate") includePrivate: Boolean,
        pageable: Pageable
    ): Page<Bundle>

    @Query(
        """
        select bundle from Bundle bundle
        where bundle.bunchId = :bunchId
          and bundle.id = :bundleId
          and bundle.status = com.bunch.bundle.domain.BundleStatus.PUBLISHED
          and (:includePrivate = true or bundle.visibility = com.bunch.bunch.domain.Visibility.PUBLIC)
        """
    )
    fun findPublishedBundle(
        @Param("bunchId") bunchId: Long,
        @Param("bundleId") bundleId: Long,
        @Param("includePrivate") includePrivate: Boolean
    ): Bundle?

    @Query(
        """
        select new com.bunch.bunch.dto.BunchPublicBundleCover(photo.bundleId, asset.url)
        from BundlePhoto photo
        join photo.imageAsset asset
        where photo.bundleId in :bundleIds
          and not exists (
              select earlier.id from BundlePhoto earlier
              where earlier.bundleId = photo.bundleId
                and (
                    earlier.position < photo.position
                    or (earlier.position = photo.position and earlier.id < photo.id)
                )
          )
        """
    )
    fun findFirstCoverImages(@Param("bundleIds") bundleIds: Collection<Long>): List<BunchPublicBundleCover>

    @Query(
        """
        select new com.bunch.bunch.dto.BunchPublicBundlePhoto(photo.id, asset.url, photo.position, photo.caption)
        from BundlePhoto photo
        join photo.imageAsset asset
        where photo.bundleId = :bundleId
        order by photo.position asc, photo.id asc
        """
    )
    fun findBundlePhotos(@Param("bundleId") bundleId: Long): List<BunchPublicBundlePhoto>

    @Query(
        """
        select count(membership) from BunchMembership membership
        join membership.member member
        where membership.bunch.id = :bunchId
          and membership.status = com.bunch.bunch.domain.MembershipStatus.ACTIVE
          and member.accountStatus = com.bunch.member.domain.AccountStatus.ACTIVE
        """
    )
    fun countActiveMembers(@Param("bunchId") bunchId: Long): Long
}
