package com.bunch.recommendation.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

interface RecommendedBundlePhotoRepository : JpaRepository<RecommendedBundlePhoto, Long> {
    fun findByBundleIdIn(bundleIds: Collection<Long>): List<RecommendedBundlePhoto>

    /** The rows of the photos in whichever recommended bundle they are, a photo being in one only (7-10). */
    fun findByPhotoIdIn(photoIds: Collection<Long>): List<RecommendedBundlePhoto>

    // Deleted before the photos of a day are written again, so a photo that moves to another bundle of the day is not
    // in two at once (a photo is in one bundle only, 7-10). The context is cleared: the rows are gone.
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RecommendedBundlePhoto p where p.bundleId in :bundleIds")
    fun deleteByBundleIds(@Param("bundleIds") bundleIds: Collection<Long>): Int
}
