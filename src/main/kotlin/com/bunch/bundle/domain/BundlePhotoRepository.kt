package com.bunch.bundle.domain

import org.springframework.data.jpa.repository.JpaRepository

interface BundlePhotoRepository : JpaRepository<BundlePhoto, Long> {
    fun existsByIdAndBundleId(id: Long, bundleId: Long): Boolean

    fun findAllByBundleIdOrderByPositionAscIdAsc(bundleId: Long): List<BundlePhoto>

    fun findAllByPhotoIdOrderByBundleIdAscIdAsc(photoId: Long): List<BundlePhoto>
}
