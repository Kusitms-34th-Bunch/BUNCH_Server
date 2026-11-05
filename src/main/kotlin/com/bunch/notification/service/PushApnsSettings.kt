package com.bunch.notification.service

import com.bunch.notification.domain.push.PushDeviceEnvironment
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI

@Component
class PushApnsSettings(
    @Value("\${notification.push.apns.team-id:}")
    val teamId: String,
    @Value("\${notification.push.apns.key-id:}")
    val keyId: String,
    @Value("\${notification.push.apns.private-key:}")
    val privateKey: String,
    @Value("\${notification.push.apns.sandbox-topic:}")
    private val sandboxTopic: String,
    @Value("\${notification.push.apns.production-topic:}")
    private val productionTopic: String,
    @Value("\${notification.push.apns.sandbox-endpoint:https://api.sandbox.push.apple.com}")
    private val sandboxEndpoint: String,
    @Value("\${notification.push.apns.production-endpoint:https://api.push.apple.com}")
    private val productionEndpoint: String
) {
    fun topic(environment: PushDeviceEnvironment): String = when (environment) {
        PushDeviceEnvironment.SANDBOX -> sandboxTopic
        PushDeviceEnvironment.PRODUCTION -> productionTopic
    }.also { if (it.isBlank()) throw BusinessException(ErrorCode.INVALID_INPUT) }

    fun endpoint(environment: PushDeviceEnvironment): URI = when (environment) {
        PushDeviceEnvironment.SANDBOX -> sandboxEndpoint
        PushDeviceEnvironment.PRODUCTION -> productionEndpoint
    }.let(::URI).also(::validateEndpoint)

    fun requireProviderCredentials() {
        require(teamId.isNotBlank() && keyId.isNotBlank() && privateKey.isNotBlank()) {
            "APNs provider credentials are not configured"
        }
    }

    private fun validateEndpoint(endpoint: URI) {
        val loopback = endpoint.host in setOf("127.0.0.1", "localhost", "[::1]")
        require(
            (endpoint.scheme == "https" || (endpoint.scheme == "http" && loopback)) &&
                !endpoint.host.isNullOrBlank() && endpoint.rawUserInfo == null &&
                endpoint.rawQuery == null && endpoint.rawFragment == null
        ) { "Invalid APNs endpoint" }
    }
}
