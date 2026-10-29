package com.bunch.bundle.service

data class BundleRealtimeCommandDestination(
    val destination: String,
    val bunchId: Long,
    val bundleId: Long,
    val command: BundleRealtimeCommand
)
