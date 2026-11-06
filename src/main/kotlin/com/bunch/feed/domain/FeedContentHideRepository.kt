package com.bunch.feed.domain

import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

interface FeedContentHideRepository : Repository<FeedContentHide, Long> {
    // Must join the successful report transaction; duplicate reports share one suppression row.
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(
        value = """
        insert into feed_content_hides(target_kind, target_id, reporter_bunch_id, reason)
        values (:#{#kind.name()}, :targetId, :reporterBunchId, 'REPORTED')
        on conflict (target_kind, target_id, reporter_bunch_id) do nothing
        """,
        nativeQuery = true
    )
    fun hideForReporter(kind: FeedContentKind, targetId: Long, reporterBunchId: Long): Int

    @Query(
        """
        select hide.targetId from FeedContentHide hide
        where hide.targetKind = :kind and hide.targetId in :targetIds
          and (hide.reporterBunchId is null or hide.reporterBunchId = :currentBunchId)
        """
    )
    fun hiddenTargetIds(kind: FeedContentKind, targetIds: Collection<Long>, currentBunchId: Long?): Set<Long>
}
