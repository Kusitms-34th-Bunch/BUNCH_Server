package com.bunch.bundle.dto

import com.bunch.bundle.domain.BundleLocationIntent
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import jakarta.validation.constraints.Size

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

data class BundleLocationIntentResponse(
    val bundleId: Long,
    val revision: Long,
    val locationVersion: Long,
    val changed: Boolean,
    val acceptedCount: Int,
    val locations: List<BundleLocationResponse>
)
