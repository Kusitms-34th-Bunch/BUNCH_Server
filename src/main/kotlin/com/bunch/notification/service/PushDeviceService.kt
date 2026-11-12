package com.bunch.notification.service

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.member.domain.MemberRepository
import com.bunch.notification.domain.push.PushDevice
import com.bunch.notification.domain.push.PushDeviceRepository
import jakarta.persistence.EntityManager
import com.bunch.notification.dto.PushDeviceRegistrationRequest
import com.bunch.notification.dto.PushDeviceResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

@Service
class PushDeviceService(
    private val members: MemberRepository,
    private val devices: PushDeviceRepository,
    private val jobs: PushJobWriter,
    private val sessions: RefreshTokenRepository,
    private val settings: PushApnsSettings,
    private val cipher: DeviceTokenCipher,
    private val entityManager: EntityManager,
    private val clock: Clock
) {
    @Transactional
    fun register(
        memberId: Long,
        sessionId: UUID,
        installationId: UUID,
        request: PushDeviceRegistrationRequest
    ): PushDeviceResponse {
        members.findLockedById(memberId)?.requireCompleted() ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
        requireActiveSession(memberId, sessionId)
        val token = normalizeToken(request.deviceToken)
        val topic = settings.topic(request.environment)
        val tokenHash = cipher.hash(token)
        lockRegistration(installationId, request.environment.name, topic, tokenHash)
        val registrations = devices.findForRegistrationLocked(installationId, request.environment, topic, tokenHash)
        val current = registrations.find { it.installationId == installationId }
        if (
            current != null && current.memberId == memberId && current.sessionId == sessionId &&
            current.environment == request.environment && current.topic == topic &&
            current.tokenHash == tokenHash && current.authorizationStatus == request.authorizationStatus
        ) {
            return PushDeviceResponse(
                installationId = current.installationId,
                environment = current.environment,
                authorizationStatus = current.authorizationStatus,
                registeredAt = current.registeredAt
            )
        }
        val registrationAt = nextRegistrationAt(clock.instant(), current?.registeredAt)

        registrations
            .filter { it.id != current?.id }
            .forEach { previous ->
                jobs.cancelPendingForDevice(previous.memberId, previous.id)
                devices.delete(previous)
            }
        devices.flush()

        val device = current ?: PushDevice(
            installationId = installationId,
            memberId = memberId,
            sessionId = sessionId,
            environment = request.environment,
            topic = topic,
            tokenCiphertext = cipher.encrypt(token),
            tokenHash = tokenHash,
            authorizationStatus = request.authorizationStatus,
            registeredAt = registrationAt
        )
        if (current != null) {
            jobs.cancelPendingForDevice(current.memberId, current.id)
            device.memberId = memberId
            device.sessionId = sessionId
            device.environment = request.environment
            device.topic = topic
            device.tokenCiphertext = cipher.encrypt(token)
            device.tokenHash = tokenHash
            device.authorizationStatus = request.authorizationStatus
            device.registeredAt = registrationAt
        }
        val saved = devices.saveAndFlush(device)
        return PushDeviceResponse(
            installationId = saved.installationId,
            environment = saved.environment,
            authorizationStatus = saved.authorizationStatus,
            registeredAt = saved.registeredAt
        )
    }

    @Transactional
    fun unregister(memberId: Long, sessionId: UUID, installationId: UUID) {
        members.findLockedById(memberId)?.requireCompleted() ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
        requireActiveSession(memberId, sessionId)
        val device = devices.findByInstallationIdLocked(installationId) ?: return
        if (device.memberId != memberId || device.sessionId != sessionId) {
            throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        }
        jobs.cancelPendingForDevice(memberId, device.id)
        devices.delete(device)
        devices.flush()
    }

    // Called when the account is withdrawn: every device and every queued or finished push of the member goes (7.6.4).
    @Transactional(propagation = Propagation.MANDATORY)
    fun removeAllOf(memberId: Long) {
        jobs.deleteAllOf(memberId)
        devices.deleteByMemberId(memberId)
    }

    @Transactional
    fun logoutSession(memberId: Long, sessionId: UUID) {
        devices.findBySessionLocked(memberId, sessionId)
        jobs.cancelPendingForSession(memberId, sessionId)
        devices.deleteByMemberIdAndSessionId(memberId, sessionId)
    }

    private fun requireActiveSession(memberId: Long, sessionId: UUID) {
        if (!sessions.isActive(sessionId.toString(), memberId)) {
            throw BusinessException(ErrorCode.UNAUTHORIZED)
        }
    }

    private fun lockRegistration(
        installationId: UUID,
        environment: String,
        topic: String,
        tokenHash: String
    ) {
        listOf(
            "push-device:installation:$installationId",
            "push-device:token:$environment:$topic:$tokenHash"
        ).sorted().forEach { key ->
            entityManager.createNativeQuery(
                "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:lockKey, 0))"
            ).setParameter("lockKey", key).singleResult
        }
    }

    private fun normalizeToken(value: String): String {
        if (
            value.length !in 2..4096 ||
            value.length % 2 != 0 ||
            !value.matches(HEX_TOKEN)
        ) {
            throw BusinessException(ErrorCode.INVALID_INPUT)
        }
        return value.lowercase(Locale.ROOT)
    }

    private fun nextRegistrationAt(now: Instant, previous: Instant?): Instant {
        val candidate = now.truncatedTo(ChronoUnit.MICROS)
        return if (previous != null && candidate <= previous) {
            previous.plus(1, ChronoUnit.MICROS)
        } else {
            candidate
        }
    }

    private companion object {
        val HEX_TOKEN = Regex("[0-9a-fA-F]+")
    }
}
