package com.bunch.bundle.service

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
class BundleRealtimeSessionRegistry {
    private val identities = ConcurrentHashMap<String, BundleRealtimeIdentity>()
    private val subscriptions = ConcurrentHashMap<String, ConcurrentHashMap<String, BundleRealtimeSubscription>>()

    fun bind(connectionId: String, identity: BundleRealtimeIdentity): Boolean {
        if (identities.putIfAbsent(connectionId, identity) != null) return false
        subscriptions[connectionId] = ConcurrentHashMap()
        return true
    }

    fun identity(connectionId: String?): BundleRealtimeIdentity? =
        connectionId?.let(identities::get)

    fun addSubscription(
        connectionId: String,
        subscription: BundleRealtimeSubscription
    ): Boolean {
        val current = subscriptions[connectionId] ?: return false
        return current.putIfAbsent(subscription.id, subscription) == null
    }

    fun removeSubscription(connectionId: String, subscriptionId: String): Boolean =
        subscriptions[connectionId]?.remove(subscriptionId) != null

    fun hasSubscription(connectionId: String, destination: String): Boolean =
        subscriptions[connectionId]?.values?.any { it.destination == destination } == true

    fun topicSubscription(
        connectionId: String,
        destination: String
    ): BundleRealtimeSubscription? =
        subscriptions[connectionId]?.values?.firstOrNull {
            it.destination == destination && it.bundleId != null
        }

    fun remove(connectionId: String) {
        identities.remove(connectionId)
        subscriptions.remove(connectionId)
    }
}
