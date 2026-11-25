package com.bunch.bunch.domain

import com.bunch.bundle.domain.Bundle
import com.bunch.bunch.dto.BunchPublicBundleCover
import com.bunch.bunch.domain.PublicBundlePhotoRow
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param

interface BunchPublicRepository : Repository<Bundle, Long> {
    @Query(
        """
        select new com.bunch.bunch.dto.BunchPublicBundleCover(photo.bundleId, asset.objectKey)
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
        select new com.bunch.bunch.domain.PublicBundlePhotoRow(photo.id, asset.objectKey, photo.position)
        from BundlePhoto photo
        join photo.imageAsset asset
        where photo.bundleId = :bundleId
        order by photo.position asc, photo.id asc
        """
    )
    fun findBundlePhotos(@Param("bundleId") bundleId: Long): List<PublicBundlePhotoRow>

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
