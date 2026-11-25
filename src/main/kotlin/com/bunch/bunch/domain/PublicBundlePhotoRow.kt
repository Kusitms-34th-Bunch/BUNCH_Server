package com.bunch.bunch.domain

/** Internal projection; signing happens in the authorized public-bundle use case. */
data class PublicBundlePhotoRow(val id: Long, val objectKey: String, val position: Int)
