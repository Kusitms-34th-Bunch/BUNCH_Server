package com.bunch.bundle.dto

import com.bunch.bunch.domain.Visibility
import com.bunch.bundle.domain.BundleStatus
import com.bunch.bundle.domain.BundleLocationIntent
import com.bunch.bundle.domain.BundlePhotoCapacityMode
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import java.time.LocalDate
import jakarta.validation.constraints.Size
import java.util.UUID

data class CreateBundleRequest(
    @param:JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.FAIL)
    val photoIds: List<Long>,

    val recommendationId: Long? = null,

    @param:JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.FAIL)
    val participantMemberIds: List<Long> = emptyList()
)

data class BundleTextRequest(
    val value: String,
    val expectedVersion: Long,
    val mode: BundleStatus,
    val leaseToken: UUID? = null
)

data class BundleVisibilityRequest(
    val value: Visibility,

    @param:JsonProperty(value = "expectedVersion", required = true)
    @param:JsonSetter(nulls = Nulls.FAIL)
    val expectedVersion: Long,
    val mode: BundleStatus
)

data class BundleDateRequest(
    val value: LocalDate?,
    val expectedVersion: Long,
    val mode: BundleStatus,
    val leaseToken: UUID? = null
)

data class BundleLocationInput(
    val provider: String,
    val externalId: String,
    val name: String,

    @param:JsonProperty(value = "address", required = true)
    val address: String?,

    @param:JsonProperty(value = "position", required = true)
    @param:JsonSetter(nulls = Nulls.FAIL)
    val position: Int
)

data class BundleLocationsRequest(
    val locations: List<BundleLocationInput>,

    @param:JsonProperty(value = "expectedVersion", required = true)
    @param:JsonSetter(nulls = Nulls.FAIL)
    val expectedVersion: Long,
    val leaseToken: UUID? = null
)

data class BundleParticipantRequest(val memberId: Long)

data class BundlePhotoChangesRequest(
    val addPhotoIds: List<Long> = emptyList(),
    val removeBundlePhotoIds: List<Long> = emptyList(),
    val mode: BundleStatus,
    val capacityMode: BundlePhotoCapacityMode = BundlePhotoCapacityMode.ATOMIC_APPLY,
    @param:JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.FAIL)
    val reselectedPhotoIds: List<Long> = emptyList()
)

data class MoveBundlePhotoRequest(
    val previousBundlePhotoId: Long? = null,
    val nextBundlePhotoId: Long? = null,
    val mode: BundleStatus
)

data class PublishBundleRequest(
    val expectedRevision: Long? = null,
    val visibility: Visibility? = null
)


data class EditPublishedBundleRequest(
    val expectedRevision: Long,
    val title: String,
    val date: LocalDate?,
    val photos: List<PublishedBundlePhotoInput>,
    val locations: List<BundleLocationInput>
)

data class PublishedBundlePhotoInput(
    val bundlePhotoId: Long? = null,
    val photoId: Long? = null
)


data class BundlePhotoPickRequest(
    @param:JsonProperty(value = "picked", required = true)
    @param:JsonSetter(nulls = Nulls.FAIL)
    val picked: Boolean,

    @param:JsonProperty(value = "expectedVersion", required = true)
    @param:JsonSetter(nulls = Nulls.FAIL)
    val expectedVersion: Long
)

data class BundleTitleCrdtRequest(val state: ByteArray)

data class BundleLocationIntentPlace(
    @param:JsonProperty(value = "provider", required = true)
    @param:JsonSetter(nulls = Nulls.FAIL)
    val provider: String,

    @param:JsonProperty(value = "externalId", required = true)
    @param:JsonSetter(nulls = Nulls.FAIL)
    val externalId: String,

    @param:JsonProperty(value = "name", required = true)
    @param:JsonSetter(nulls = Nulls.FAIL)
    val name: String,

    val address: String?
)

data class BundleLocationIntentRequest(
    @param:JsonProperty(value = "intent", required = true)
    @param:JsonSetter(nulls = Nulls.FAIL)
    val intent: BundleLocationIntent,

    @field:Size(max = 3)
    @param:JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.FAIL)
    val locations: List<BundleLocationIntentPlace> = emptyList(),

    val location: BundleLocationIntentPlace? = null,
    val targetLocationId: Long? = null,
    val previousLocationId: Long? = null,
    val nextLocationId: Long? = null
)
