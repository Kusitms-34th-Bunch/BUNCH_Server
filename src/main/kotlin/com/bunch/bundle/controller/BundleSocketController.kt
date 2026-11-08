package com.bunch.bundle.controller

import com.bunch.bundle.domain.BundleEventType
import com.bunch.bundle.domain.BundleActivityType
import com.bunch.bundle.dto.BundleRealtimeMessage
import com.bunch.bundle.dto.BundleCommandRejectedData
import com.bunch.bundle.dto.BundleActivityData
import com.bunch.bundle.dto.BundleLeaseReleasedData
import com.bunch.bundle.dto.BundleActivityCommand
import com.bunch.bundle.dto.BundleActivityEvent
import com.bunch.bundle.dto.BundleActor
import com.bunch.bundle.dto.BundleLeaseData
import com.bunch.bundle.dto.BundleLeaseCommand
import com.bunch.bundle.dto.BundleLeaseReleaseAck
import com.bunch.bundle.service.BundleAccessService
import com.bunch.bundle.service.BundleLeaseService
import com.bunch.bundle.service.BundleRealtimeSessionRegistry
import com.bunch.bundle.service.BundleRealtimeDestinations
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.security.JwtSessionDetails
import org.springframework.context.ApplicationEventPublisher
import org.springframework.messaging.handler.annotation.DestinationVariable
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.MessageExceptionHandler
import org.springframework.messaging.simp.annotation.SendToUser
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import java.util.Locale

@Controller
class BundleSocketController(
    private val access: BundleAccessService,
    private val leases: BundleLeaseService,
    private val events: ApplicationEventPublisher,
    private val registry: BundleRealtimeSessionRegistry
) {
    @MessageMapping("/bunches/{bunchId}/bundles/{bundleId}/lease/acquire")
    @SendToUser(destinations = ["/queue/bundle-leases"], broadcast = false)
    fun acquire(
        headers: StompHeaderAccessor,
        @DestinationVariable("bunchId") bunchId: Long,
        @DestinationVariable("bundleId") bundleId: Long,
        command: BundleLeaseCommand
    ): BundleRealtimeMessage<BundleLeaseData> {
        val connectionId = connectionId(headers)
        val grant = leases.acquire(
            actor = actor(headers),
            bunchId = bunchId,
            bundleId = bundleId,
            target = command.target,
            connectionId = connectionId
        )
        // Acquire has committed before this check. A disconnect that ran before that
        // commit could not see the lease row; a later disconnect can now find it.
        if (registry.identity(connectionId) == null) {
            leases.clearConnection(connectionId)
            fail(ErrorCode.UNAUTHORIZED)
        }
        return grant.toMessage(BundleEventType.LEASE_GRANTED)
    }

    @MessageMapping("/bunches/{bunchId}/bundles/{bundleId}/lease/heartbeat")
    @SendToUser(destinations = ["/queue/bundle-leases"], broadcast = false)
    fun heartbeat(
        headers: StompHeaderAccessor,
        @DestinationVariable("bunchId") bunchId: Long,
        @DestinationVariable("bundleId") bundleId: Long,
        command: BundleLeaseCommand
    ) = leases.heartbeat(
        actor = actor(headers),
        bunchId = bunchId,
        bundleId = bundleId,
        target = command.target,
        token = command.token ?: fail(ErrorCode.BUNDLE_LEASE_CONFLICT),
        connectionId = connectionId(headers)
    ).toMessage(BundleEventType.LEASE_HEARTBEAT)

    @MessageMapping("/bunches/{bunchId}/bundles/{bundleId}/lease/release")
    @SendToUser(destinations = ["/queue/bundle-leases"], broadcast = false)
    fun release(
        headers: StompHeaderAccessor,
        @DestinationVariable("bunchId") bunchId: Long,
        @DestinationVariable("bundleId") bundleId: Long,
        command: BundleLeaseCommand
    ): BundleRealtimeMessage<BundleLeaseReleasedData> {
        val actor = actor(headers)
        leases.release(
            actor = actor,
            bunchId = bunchId,
            bundleId = bundleId,
            target = command.target,
            token = command.token ?: fail(ErrorCode.BUNDLE_LEASE_CONFLICT),
            connectionId = connectionId(headers)
        )
        val bundle = access.read(actor.memberId, bunchId, bundleId)
        return BundleLeaseReleaseAck(bunchId, bundleId, command.target, bundle.revision).toMessage()
    }

    @MessageMapping("/bunches/{bunchId}/bundles/{bundleId}/activity")
    @SendToUser(destinations = ["/queue/bundle-activity"], broadcast = false)
    fun activity(
        headers: StompHeaderAccessor,
        @DestinationVariable("bunchId") bunchId: Long,
        @DestinationVariable("bundleId") bundleId: Long,
        command: BundleActivityCommand
    ): BundleRealtimeMessage<BundleActivityData> {
        val actor = actor(headers)
        val bundle = access.read(actor.memberId, bunchId, bundleId)
        val type = try {
            BundleActivityType.valueOf(command.type.trim().uppercase(Locale.ROOT))
        } catch (_: IllegalArgumentException) {
            fail(ErrorCode.INVALID_INPUT)
        }
        when (type) {
            BundleActivityType.COMMENT -> access.requireParticipant(actor.memberId, bundle)
            BundleActivityType.VIEW -> Unit
        }
        val event = BundleActivityEvent(
            bunchId = bunchId,
            bundleId = bundleId,
            memberId = actor.memberId,
            type = type,
            revision = bundle.revision
        )
        events.publishEvent(event)
        return event.toMessage()
    }

    @MessageExceptionHandler(Exception::class)
    @SendToUser(destinations = ["/queue/bundle-leases"], broadcast = false)
    fun rejected(headers: StompHeaderAccessor, error: Exception): BundleRealtimeMessage<BundleCommandRejectedData> {
        val destination = BundleRealtimeDestinations.parseCommand(headers.destination)
        val code = generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<BusinessException>().firstOrNull()?.errorCode?.code ?: "WS_001"
        return BundleRealtimeMessage(
            type = BundleEventType.COMMAND_REJECTED,
            bunchId = destination?.bunchId,
            bundleId = destination?.bundleId,
            data = BundleCommandRejectedData(code)
        )
    }

    private fun actor(headers: StompHeaderAccessor): BundleActor {
        val authentication = headers.user as? Authentication ?: fail(ErrorCode.UNAUTHORIZED)
        val memberId = authentication.principal.toString().toLongOrNull()
            ?.takeIf { it > 0 }
            ?: fail(ErrorCode.UNAUTHORIZED)
        val sessionId = (authentication.details as? JwtSessionDetails)?.sessionId
            ?: fail(ErrorCode.UNAUTHORIZED)
        return BundleActor(memberId, sessionId)
    }

    private fun connectionId(headers: StompHeaderAccessor): String =
        headers.sessionId?.takeIf { it.isNotBlank() } ?: fail(ErrorCode.UNAUTHORIZED)

    private fun fail(code: ErrorCode): Nothing = throw BusinessException(code)
}
