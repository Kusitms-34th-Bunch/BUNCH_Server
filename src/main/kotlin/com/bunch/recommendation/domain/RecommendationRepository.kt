package com.bunch.recommendation.domain

import com.bunch.photo.domain.Photo
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param

// The photos a recommended bundle can be made of: READY photos with a date that no bundle uses. A bundle photo is
// only read here. Draft and published bundles hide their photos the same way, and a deleted draft frees them again.
private const val MATERIAL_ROWS =
    "select p.id as photoId, p.capturedDate as capturedDate, p.capturedTime as capturedTime, " +
        "u.member.id as memberId, u.uploadBatchId as uploadBatchId " +
        "from PhotoUploader u join u.photo p " +
        "where p.bunch.id = :bunchId and p.processingStatus = com.bunch.photo.domain.ProcessingStatus.READY " +
        "and p.capturedDate is not null " +
        "and not exists (select 1 from BundlePhoto b where b.photoId = p.id) " +
        "order by p.id asc, u.id asc"

interface RecommendationRepository : Repository<Photo, Long> {
    @Query(MATERIAL_ROWS)
    fun findMaterialRows(@Param("bunchId") bunchId: Long): List<MaterialPhotoRow>
}
