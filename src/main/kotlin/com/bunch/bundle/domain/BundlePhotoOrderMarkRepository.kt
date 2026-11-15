package com.bunch.bundle.domain

import org.springframework.data.jpa.repository.JpaRepository

interface BundlePhotoOrderMarkRepository : JpaRepository<BundlePhotoOrderMark, Long> {
    fun findAllByBundleId(bundleId: Long): List<BundlePhotoOrderMark>
}
