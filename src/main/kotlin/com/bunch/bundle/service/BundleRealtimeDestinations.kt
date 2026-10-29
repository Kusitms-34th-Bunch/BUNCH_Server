package com.bunch.bundle.service

object BundleRealtimeDestinations {
    const val APPLICATION_PREFIX = "/app"
    const val TOPIC_PREFIX = "/topic"
    const val USER_PREFIX = "/user"
    const val LEASE_BROKER_QUEUE = "/queue/bundle-leases"
    const val ACTIVITY_BROKER_QUEUE = "/queue/bundle-activity"
    const val LEASE_QUEUE = "/user/queue/bundle-leases"
    const val ACTIVITY_QUEUE = "/user/queue/bundle-activity"

    private val topicPattern = Regex("^/topic/bunches/([1-9][0-9]*)/bundles/([1-9][0-9]*)$")
    private val commandPattern = Regex(
        "^/app/bunches/([1-9][0-9]*)/bundles/([1-9][0-9]*)/" +
            "(lease/acquire|lease/heartbeat|lease/release|activity)$"
    )

    fun topic(bunchId: Long, bundleId: Long): String =
        "$TOPIC_PREFIX/bunches/$bunchId/bundles/$bundleId"

    fun parseTopic(destination: String?): BundleRealtimeTopicDestination? {
        if (destination == null) return null
        topicPattern.matchEntire(destination)?.let { match ->
            return BundleRealtimeTopicDestination(
                destination = destination,
                bunchId = match.groupValues[1].toLong(),
                bundleId = match.groupValues[2].toLong()
            )
        }
        return null
    }

    fun parseCommand(destination: String?): BundleRealtimeCommandDestination? {
        if (destination == null) return null
        val match = commandPattern.matchEntire(destination) ?: return null
        val command = when (match.groupValues[3]) {
            "lease/acquire" -> BundleRealtimeCommand.ACQUIRE
            "lease/heartbeat" -> BundleRealtimeCommand.HEARTBEAT
            "lease/release" -> BundleRealtimeCommand.RELEASE
            "activity" -> BundleRealtimeCommand.ACTIVITY
            else -> return null
        }
        return BundleRealtimeCommandDestination(
            destination = destination,
            bunchId = match.groupValues[1].toLong(),
            bundleId = match.groupValues[2].toLong(),
            command = command
        )
    }

    fun isPrivateSubscription(destination: String?): Boolean =
        destination == LEASE_QUEUE || destination == ACTIVITY_QUEUE

    fun isPrivateDelivery(destination: String?, rawDestination: String): Boolean {
        if (destination == null) return false
        if (destination == rawDestination) return true
        val brokerDestination = rawDestination.removePrefix(USER_PREFIX)
        return destination.startsWith("$brokerDestination-user")
    }
}
