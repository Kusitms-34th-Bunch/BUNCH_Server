package com.bunch.photo.domain

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.LocalTime

data class PhotoListRow(
    val id: Long,
    val capturedDate: LocalDate,
    val capturedTime: LocalTime?,
    val imageUrl: String
)

/**
 * Reads for the photo box list. The query is assembled per request because an optional filter and an
 * optional cursor would otherwise need a query for every combination.
 */
@Repository
class PhotoListRepository {
    @PersistenceContext
    private lateinit var entityManager: EntityManager

    /** Up to [limit] READY photos with a date, after [after], uploaded by [uploaderId] when it is given. */
    fun findPage(bunchId: Long, uploaderId: Long?, after: PhotoListCursor?, limit: Int): List<PhotoListRow> {
        val jpql = StringBuilder(
            """select p.id, p.capturedDate, p.capturedTime, a.url from Photo p join p.imageAsset a
                where p.bunch.id = :bunchId and p.processingStatus = :ready and p.capturedDate is not null"""
        )
        if (uploaderId != null) jpql.append(UPLOADED_BY)
        if (after != null) jpql.append(if (after.time != null) AFTER_WITH_TIME else AFTER_WITHOUT_TIME)
        jpql.append(" order by p.capturedDate desc, p.capturedTime desc nulls last, p.id asc")

        val query = entityManager.createQuery(jpql.toString(), Array<Any>::class.java)
            .setParameter("bunchId", bunchId)
            .setParameter("ready", ProcessingStatus.READY)
            .setMaxResults(limit)
        if (uploaderId != null) query.setParameter("uploaderId", uploaderId)
        if (after != null) {
            query.setParameter("cursorDate", after.date).setParameter("cursorId", after.id)
            if (after.time != null) query.setParameter("cursorTime", after.time)
        }
        return query.resultList.map {
            PhotoListRow(it[0] as Long, it[1] as LocalDate, it[2] as LocalTime?, it[3] as String)
        }
    }

    /** Every photo of the bunch, whatever its date or state. The 1,000 photo limit counts all of them. */
    fun countAll(bunchId: Long): Long =
        entityManager.createQuery(
            "select count(p) from Photo p where p.bunch.id = :bunchId",
            Long::class.javaObjectType
        )
            .setParameter("bunchId", bunchId)
            .singleResult

    /** Uploader member IDs only, in the order they uploaded. No member entity is loaded. */
    fun findUploaderIds(photoIds: Collection<Long>): Map<Long, List<Long>> {
        if (photoIds.isEmpty()) return emptyMap()
        return entityManager.createQuery(
            """select u.photo.id, u.member.id from PhotoUploader u
                where u.photo.id in :photoIds order by u.photo.id asc, u.id asc""",
            Array<Any>::class.java
        )
            .setParameter("photoIds", photoIds)
            .resultList
            .groupBy({ it[0] as Long }, { it[1] as Long })
    }

    private companion object {
        const val UPLOADED_BY =
            " and exists (select 1 from PhotoUploader u where u.photo.id = p.id and u.member.id = :uploaderId)"

        // Rows after the cursor: an earlier date, or the same date with a later position. A photo without a
        // time sorts after every photo with one, so a cursor with a time also reaches the photos without.
        const val AFTER_WITH_TIME =
            " and (p.capturedDate < :cursorDate or (p.capturedDate = :cursorDate and " +
                "(p.capturedTime < :cursorTime or p.capturedTime is null or " +
                "(p.capturedTime = :cursorTime and p.id > :cursorId))))"
        const val AFTER_WITHOUT_TIME =
            " and (p.capturedDate < :cursorDate or (p.capturedDate = :cursorDate and " +
                "p.capturedTime is null and p.id > :cursorId))"
    }
}
