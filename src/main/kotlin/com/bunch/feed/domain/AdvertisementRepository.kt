package com.bunch.feed.domain

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface AdvertisementRepository : JpaRepository<Advertisement, Long> {
    @Query(value = """
        SELECT a.* FROM feed_advertisements a
        WHERE a.active AND NOT EXISTS (
            SELECT 1 FROM feed_advertisement_hides h
            WHERE h.advertisement_id = a.id AND h.member_id = :memberId
        ) ORDER BY a.id
    """, nativeQuery = true)
    fun findEligible(memberId: Long): List<Advertisement>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Advertisement a where a.id = :id")
    fun findLocked(id: Long): Advertisement?

    @Query(value = """
        SELECT EXISTS (SELECT 1 FROM feed_advertisement_hides
            WHERE member_id = :memberId AND advertisement_id = :advertisementId)
    """, nativeQuery = true)
    fun isHidden(memberId: Long, advertisementId: Long): Boolean

    @Modifying
    @Query(value = """
        INSERT INTO feed_advertisement_hides (member_id, advertisement_id)
        VALUES (:memberId, :advertisementId) ON CONFLICT DO NOTHING
    """, nativeQuery = true)
    fun hide(memberId: Long, advertisementId: Long): Int
}
