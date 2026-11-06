package com.bunch.bundle.dto

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import java.util.UUID

data class BundleLocationInput(
    val provider: String,
    val externalId: String,
    val name: String,
    val address: String,
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

data class BundleLocationResponse(
    val id: Long,
    val provider: String,
    val externalId: String,
    val name: String,
    val address: String,
    val position: Int
)

data class BundleLocationState(
    val bundleId: Long,
    val revision: Long,
    val locationVersion: Long,
    val locations: List<BundleLocationResponse>
)

data class BundleLocationChange(
    val changed: Boolean,
    val locations: List<BundleLocationResponse>
)
