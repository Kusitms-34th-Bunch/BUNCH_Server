package com.bunch.bundle.dto

import com.bunch.bunch.domain.Visibility
import com.bunch.bundle.domain.BundleStatus
import java.time.LocalDate
import java.util.UUID

data class CreateBundleRequest(val photoIds: List<Long>)

data class BundleTextRequest(
    val value: String,
    val expectedVersion: Long,
    val mode: BundleStatus,
    val leaseToken: UUID? = null
)

data class BundleVisibilityRequest(
    val value: Visibility,
    val expectedVersion: Long,
    val mode: BundleStatus
)

data class BundleDateRequest(
    val value: LocalDate?,
    val expectedVersion: Long,
    val mode: BundleStatus,
    val leaseToken: UUID? = null
)

data class BundleParticipantRequest(val memberId: Long)

data class BundlePhotoChangesRequest(
    val addPhotoIds: List<Long> = emptyList(),
    val removeBundlePhotoIds: List<Long> = emptyList(),
    val mode: BundleStatus
)

data class MoveBundlePhotoRequest(
    val previousBundlePhotoId: Long? = null,
    val nextBundlePhotoId: Long? = null,
    val mode: BundleStatus
)

data class PublishBundleRequest(val expectedRevision: Long)
