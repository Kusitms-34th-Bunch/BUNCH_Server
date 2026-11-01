package com.bunch.bundle.service

data class BundleRealtimeCommandDestination(
    val bunchId: Long,
    val bundleId: Long,
    val command: BundleRealtimeCommand
)
