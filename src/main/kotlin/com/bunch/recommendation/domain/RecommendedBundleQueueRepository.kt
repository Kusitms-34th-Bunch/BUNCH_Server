package com.bunch.recommendation.domain

import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate

/**
 * The days that wait to be calculated again (recommended bundle specification 10-1, 10-2). The table has no entity: a
 * row is only a day of a bunch and the time it is due, and it is written and read with its own statements.
 */
interface RecommendedBundleQueueRepository : Repository<RecommendedBundle, Long> {
    /**
     * Puts the day in the queue to be calculated at [dueAt], or moves the time of the day that is there to [dueAt]
     * (10-2: a change before the time moves it later, so the day is calculated once, after the last change).
     */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query(
        """INSERT INTO recommended_bundle_recalc_queue (bunch_id, day_key, due_at)
            VALUES (:bunchId, :dayKey, :dueAt)
            ON CONFLICT (bunch_id, day_key) DO UPDATE SET due_at = EXCLUDED.due_at""",
        nativeQuery = true
    )
    fun put(
        @Param("bunchId") bunchId: Long,
        @Param("dayKey") dayKey: LocalDate,
        @Param("dueAt") dueAt: Instant
    ): Int

    /**
     * Puts every day of the dated photos of a bunch in the queue to be calculated at [dueAt] (10-1: the photos of a
     * bundle that a member makes are not known by their day any more when the bundle is changed, so all days are
     * noted).
     */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query(
        """INSERT INTO recommended_bundle_recalc_queue (bunch_id, day_key, due_at)
            SELECT p.bunch_id, p.captured_date, CAST(:dueAt AS timestamptz) FROM photos p
            WHERE p.bunch_id = :bunchId AND p.captured_date IS NOT NULL AND p.processing_status = 'READY'
            GROUP BY p.bunch_id, p.captured_date
            ON CONFLICT (bunch_id, day_key) DO UPDATE SET due_at = EXCLUDED.due_at""",
        nativeQuery = true
    )
    fun putAllDays(@Param("bunchId") bunchId: Long, @Param("dueAt") dueAt: Instant): Int

    /**
     * Puts every day of the dated photos of every bunch in the queue to be calculated at [dueAt], and returns how many
     * rows it wrote (the first calculation of the photos that were there before the new recommended bundles were
     * switched on). A day that is already queued only gets its time moved, so running it twice makes no second row.
     */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query(
        """INSERT INTO recommended_bundle_recalc_queue (bunch_id, day_key, due_at)
            SELECT p.bunch_id, p.captured_date, CAST(:dueAt AS timestamptz) FROM photos p
            WHERE p.captured_date IS NOT NULL AND p.processing_status = 'READY'
            GROUP BY p.bunch_id, p.captured_date
            ON CONFLICT (bunch_id, day_key) DO UPDATE SET due_at = EXCLUDED.due_at""",
        nativeQuery = true
    )
    fun putAllDaysOfAllBunches(@Param("dueAt") dueAt: Instant): Int

    /** The bunches that have a day due at [now] or before, in the order of their numbers. */
    @Query(
        """SELECT DISTINCT bunch_id FROM recommended_bundle_recalc_queue
            WHERE due_at <= :now ORDER BY bunch_id LIMIT :limit""",
        nativeQuery = true
    )
    fun findDueBunchIds(@Param("now") now: Instant, @Param("limit") limit: Int): List<Long>

    /** The days of a bunch that are due at [now] or before. */
    @Query(
        """SELECT day_key AS "dayKey" FROM recommended_bundle_recalc_queue
            WHERE bunch_id = :bunchId AND due_at <= :now ORDER BY day_key""",
        nativeQuery = true
    )
    fun findDueDays(@Param("bunchId") bunchId: Long, @Param("now") now: Instant): List<QueuedDayRow>

    /**
     * Takes the days out of the queue once they are calculated. A day whose time was moved later while it was being
     * calculated (a change came) is due after [now] and stays, so the change is not lost.
     */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query(
        """DELETE FROM recommended_bundle_recalc_queue
            WHERE bunch_id = :bunchId AND day_key IN (:dayKeys) AND due_at <= :now""",
        nativeQuery = true
    )
    fun deleteDue(
        @Param("bunchId") bunchId: Long,
        @Param("dayKeys") dayKeys: Collection<LocalDate>,
        @Param("now") now: Instant
    ): Int
}
