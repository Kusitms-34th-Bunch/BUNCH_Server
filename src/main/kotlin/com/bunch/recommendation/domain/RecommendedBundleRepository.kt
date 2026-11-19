package com.bunch.recommendation.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate

interface RecommendedBundleRepository : JpaRepository<RecommendedBundle, Long> {
    /** The recommended bundles of the days of a bunch, which are read and replaced together (10-3). */
    fun findByBunchIdAndDayKeyIn(bunchId: Long, dayKeys: Collection<LocalDate>): List<RecommendedBundle>

    /** Every recommended bundle of a bunch, which the calculation of the bunch replaces (10-3). */
    fun findByBunchId(bunchId: Long): List<RecommendedBundle>

    /** The recommended bundles of a bunch that are shown at [now] (10-4): the ones whose time to be shown from came. */
    @Query("select b from RecommendedBundle b where b.bunchId = :bunchId and b.visibleFrom <= :now")
    fun findVisible(@Param("bunchId") bunchId: Long, @Param("now") now: Instant): List<RecommendedBundle>

    /** One recommended bundle of the bunch, or none when it is gone or belongs to another bunch. */
    fun findByIdAndBunchId(id: Long, bunchId: Long): RecommendedBundle?

    /**
     * Deletes the recommended bundles whose last photo was added before [cutoff] and returns how many (4.2.9 rule 4).
     * Their photos go with them (the database deletes them), and they are not recorded.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RecommendedBundle b where b.lastPhotoAddedAt < :cutoff")
    fun deleteExpired(@Param("cutoff") cutoff: Instant): Int
}
