package com.bunch.photo.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

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
}
