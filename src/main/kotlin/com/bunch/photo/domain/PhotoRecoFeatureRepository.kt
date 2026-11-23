package com.bunch.photo.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

interface PhotoRecoFeatureRepository : JpaRepository<PhotoRecoFeature, Long> {
    // Creates the row when the photo has none and fills the original type only while it is empty, so a known value
    // is never replaced. The caller holds the photo lock. A row is never read before this statement, so the
    // persistence context needs no clearing.
    @Modifying(flushAutomatically = true)
    @Query(
        """INSERT INTO photo_reco_feature (photo_id, source) VALUES (:photoId, CAST(:source AS VARCHAR))
            ON CONFLICT (photo_id) DO UPDATE SET source = EXCLUDED.source
            WHERE photo_reco_feature.source IS NULL""",
        nativeQuery = true
    )
    fun fillSource(@Param("photoId") photoId: Long, @Param("source") source: String?): Int

    // The same for the device model: the row is created when the photo has none, and a model that is known is never
    // replaced.
    @Modifying(flushAutomatically = true)
    @Query(
        """INSERT INTO photo_reco_feature (photo_id, device_model) VALUES (:photoId, CAST(:model AS VARCHAR))
            ON CONFLICT (photo_id) DO UPDATE SET device_model = EXCLUDED.device_model
            WHERE photo_reco_feature.device_model IS NULL""",
        nativeQuery = true
    )
    fun fillDeviceModel(@Param("photoId") photoId: Long, @Param("model") model: String): Int

    // The same for the quality: the row is created when the photo has none, and a quality that is known is never
    // replaced.
    @Modifying(flushAutomatically = true)
    @Query(
        """INSERT INTO photo_reco_feature (photo_id, quality) VALUES (:photoId, CAST(:quality AS VARCHAR))
            ON CONFLICT (photo_id) DO UPDATE SET quality = EXCLUDED.quality
            WHERE photo_reco_feature.quality IS NULL""",
        nativeQuery = true
    )
    fun fillQuality(@Param("photoId") photoId: Long, @Param("quality") quality: String): Int

    // The photo is asked to be analyzed (recommended bundle specification 4-3). The row is created when the photo has
    // none, and an analysis that is done is not asked again. A failed one is: the ask starts it over.
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query(
        """INSERT INTO photo_reco_feature (photo_id, ai_status) VALUES (:photoId, 'PENDING')
            ON CONFLICT (photo_id) DO UPDATE SET ai_status = 'PENDING'
            WHERE photo_reco_feature.ai_status IS DISTINCT FROM 'DONE'""",
        nativeQuery = true
    )
    fun markPending(@Param("photoId") photoId: Long): Int

    // The answer of the analysis, as the model wrote it, with the model that gave it. A late answer replaces a failed
    // one, because an answer is better than none.
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query(
        """INSERT INTO photo_reco_feature (photo_id, ai_status, ai_model_version, ai_raw, analyzed_at)
            VALUES (:photoId, 'DONE', :modelVersion, CAST(:raw AS JSONB), :analyzedAt)
            ON CONFLICT (photo_id) DO UPDATE SET ai_status = 'DONE', ai_model_version = EXCLUDED.ai_model_version,
                ai_raw = EXCLUDED.ai_raw, analyzed_at = EXCLUDED.analyzed_at""",
        nativeQuery = true
    )
    fun markDone(
        @Param("photoId") photoId: Long,
        @Param("modelVersion") modelVersion: String,
        @Param("raw") raw: String,
        @Param("analyzedAt") analyzedAt: Instant
    ): Int

    // The analysis could not be made. An answer that is already there is never replaced by a failure.
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query(
        """INSERT INTO photo_reco_feature (photo_id, ai_status, analyzed_at) VALUES (:photoId, 'FAILED', :analyzedAt)
            ON CONFLICT (photo_id) DO UPDATE SET ai_status = 'FAILED', analyzed_at = EXCLUDED.analyzed_at
            WHERE photo_reco_feature.ai_status IS DISTINCT FROM 'DONE'""",
        nativeQuery = true
    )
    fun markFailed(@Param("photoId") photoId: Long, @Param("analyzedAt") analyzedAt: Instant): Int

    // The stored answers of the photos whose analysis is done, as the text of the JSON that was stored.
    @Query(
        """SELECT photo_id AS "photoId", CAST(ai_raw AS TEXT) AS "raw" FROM photo_reco_feature
            WHERE photo_id IN (:photoIds) AND ai_status = 'DONE' AND ai_raw IS NOT NULL""",
        nativeQuery = true
    )
    fun findAnswers(@Param("photoIds") photoIds: Collection<Long>): List<AiAnswerRow>
}
