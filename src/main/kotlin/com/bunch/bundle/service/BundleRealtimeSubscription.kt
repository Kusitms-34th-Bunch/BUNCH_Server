package com.bunch.bundle.service

data class BundleRealtimeSubscription(
    val id: String,
    val destination: String,
    val bunchId: Long? = null,
    val bundleId: Long? = null
)
