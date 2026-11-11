package com.bunch.bundle.service

import com.bunch.bundle.domain.BundleEventType
import com.bunch.bundle.domain.BundleStatus
import com.bunch.bundle.domain.BundleText
import com.bunch.bundle.domain.BundlePhotoRepository
import com.bunch.bundle.dto.BundleActor
import com.bunch.bundle.dto.BundleChangedEvent
import com.bunch.bundle.dto.BundlePresenceCommand
import com.bunch.bundle.dto.BundlePresenceData
import com.bunch.bundle.dto.BundlePresenceMember
import com.bunch.bundle.dto.BundleTypingState
import com.bunch.bundle.dto.BundleEditingState
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.Locale

/** Ephemeral, nonexclusive previews. Never writes bundle content or activity. */
@Service
class BundlePresenceService(
    private val access: BundleAccessService,
    private val registry: BundleRealtimeSessionRegistry,
    private val messaging: SimpMessagingTemplate,
    private val clock: Clock,
    private val photos: BundlePhotoRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val states = mutableMapOf<Scope, MutableMap<String, Connection>>()
    private var inputSequence = 0L

    @Transactional
    fun command(
        actor: BundleActor,
        connectionId: String,
        bunchId: Long,
        bundleId: Long,
        command: BundlePresenceCommand
    ): BundlePresenceData {
        if (registry.identity(connectionId) != actor) fail(ErrorCode.UNAUTHORIZED)
        val bundle = access.lock(actor.memberId, bunchId, bundleId)
        if (bundle.status != BundleStatus.DRAFT) fail(ErrorCode.BUNDLE_STATE_CONFLICT)
        val action = command.action.trim().uppercase(Locale.ROOT)
        val target = command.target
        if (action == "BEGIN" || action == "END") {
            if (action == "BEGIN") access.requireParticipant(actor.memberId, bundle)
            if (action == "BEGIN" || target != null) validateTarget(target, bundleId, action == "BEGIN")
            if (command.text != null) fail(ErrorCode.INVALID_INPUT)
        }
        if (action == "TYPE") {
            access.requireParticipant(actor.memberId, bundle)
            if (target != "TITLE" && target != "COMMENT") fail(ErrorCode.INVALID_INPUT)
            val text = command.text ?: fail(ErrorCode.INVALID_INPUT)
            if (text.length > MAX_TEXT_BYTES || text.toByteArray(StandardCharsets.UTF_8).size > MAX_TEXT_BYTES) {
                fail(ErrorCode.INVALID_INPUT)
            }
            try {
                if (target == "COMMENT") BundleText.comment(text) else BundleText.mergedTitle(text)
            } catch (_: IllegalArgumentException) {
                fail(ErrorCode.INVALID_INPUT)
            }
        } else if (action !in setOf("VIEW", "BEGIN", "END", "STOP", "BLUR", "LEAVE", "BACKGROUND")) {
            fail(ErrorCode.INVALID_INPUT)
        }
        val participant = action in setOf("TYPE", "BEGIN") || access.isParticipant(actor.memberId, bundleId)
        // Always obtain the database lock before the presence monitor. A committed
        // publisher/removal must be able to enter changed() while another command waits.
        return synchronized(this) {
            // The disconnect interceptor can remove the identity while access checks are running.
            if (registry.identity(connectionId) != actor) fail(ErrorCode.UNAUTHORIZED)
            val scope = Scope(bunchId, bundleId)
            val now = clock.instant()
            expire(scope, now)
            if (action == "LEAVE" || action == "BACKGROUND") {
                states[scope]?.remove(connectionId)
            } else {
                val current = states.getOrPut(scope) { mutableMapOf() }
                val connection = current.getOrPut(connectionId) {
                    Connection(actor, now, participant)
                }
                connection.lastSignal = now
                connection.disconnected = false
                when (action) {
                    "TYPE" -> {
                        connection.participant = true
                        inputSequence++
                        connection.typing = Input(target!!, command.text!!, now, inputSequence)
                    }
                    "BEGIN" -> {
                        connection.participant = true
                        connection.editing = Editing(target!!, now)
                        if (connection.typing?.target != target) connection.typing = null
                    }
                    "END" -> {
                        if (target == null || connection.editing?.target == target) connection.editing = null
                        if (target == null || connection.typing?.target == target) connection.typing = null
                    }
                    "STOP", "BLUR" -> {
                        connection.typing = null
                        connection.editing = null
                    }
                }
            }
            val data = snapshot(scope)
            if (states[scope]?.isEmpty() == true) states.remove(scope)
            broadcast(scope, data)
            data
        }
    }

    @Synchronized
    fun get(memberId: Long, bunchId: Long, bundleId: Long): BundlePresenceData {
        access.read(memberId, bunchId, bundleId)
        val scope = Scope(bunchId, bundleId)
        if (expire(scope, clock.instant())) broadcast(scope, snapshot(scope))
        return snapshot(scope)
    }

    @Synchronized
    fun disconnected(connectionId: String) {
        val now = clock.instant()
        states.keys.toList().forEach { scope ->
            val connection = states[scope]?.get(connectionId) ?: return@forEach
            // Only existing, still-live typing gets the disconnect grace period.
            if (!connection.disconnected) {
                if (connection.typing?.at?.plusSeconds(TYPING_SECONDS)?.isAfter(now) == false) {
                    connection.typing = null
                }
                if (connection.editing?.at?.plusSeconds(PRESENCE_SECONDS)?.isAfter(now) == false) {
                    connection.editing = null
                }
                connection.disconnected = true
            }
            expire(scope, now)
            broadcast(scope, snapshot(scope))
        }
    }

    @Scheduled(fixedDelay = 1000)
    @Synchronized
    fun expire() {
        val now = clock.instant()
        states.keys.toList().forEach { scope ->
            if (expire(scope, now)) broadcast(scope, snapshot(scope))
        }
    }

    /** Source transactions must commit before any ephemeral state is removed. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    @Synchronized
    fun changed(event: BundleChangedEvent) {
        val scope = Scope(event.bunchId, event.bundleId)
        val current = states[scope] ?: return
        when (event.type) {
            BundleEventType.PUBLISHED, BundleEventType.DELETED -> states.remove(scope)
            BundleEventType.PARTICIPANTS, BundleEventType.PERMISSIONS -> {
                current.entries.removeIf { (_, connection) ->
                    try {
                        val bundle = access.read(connection.actor.memberId, scope.bunchId, scope.bundleId)
                        if (connection.participant) access.requireParticipant(connection.actor.memberId, bundle)
                        false
                    } catch (_: BusinessException) {
                        true
                    }
                }
                if (current.isEmpty()) states.remove(scope)
            }
            BundleEventType.PHOTOS -> {
                val ids = photos.findAllByBundleIdOrderByPositionAscIdAsc(scope.bundleId).map { it.id }.toSet()
                current.values.forEach { connection ->
                    val target = connection.editing?.target
                    if (target != null && (target.startsWith("PHOTO:") || target.startsWith("PICK:")) &&
                        target.substringAfter(':').toLong() !in ids) connection.editing = null
                }
            }
            else -> return
        }
        broadcast(scope, snapshot(scope))
    }

    private fun expire(scope: Scope, now: Instant): Boolean {
        val current = states[scope] ?: return false
        var changed = false
        current.entries.removeIf { (connectionId, connection) ->
            if (!connection.lastSignal.plusSeconds(PRESENCE_SECONDS).isAfter(now)) {
                changed = true
                true
            } else {
                if (!connection.disconnected && registry.identity(connectionId) == null) {
                    // A disconnect event may be delayed; the authenticated registry also
                    // observes expired/revoked sessions. Never revive an already idle input.
                    if (connection.typing?.at?.plusSeconds(TYPING_SECONDS)?.isAfter(now) == false) {
                        connection.typing = null
                    }
                    if (connection.editing?.at?.plusSeconds(PRESENCE_SECONDS)?.isAfter(now) == false) {
                        connection.editing = null
                    }
                    connection.disconnected = true
                    changed = true
                }
                val typing = connection.typing
                if (typing != null && !typingExpiry(connection, typing).isAfter(now)) {
                    connection.typing = null
                    changed = true
                }
                val editing = connection.editing
                if (editing != null && !editingExpiry(connection, editing).isAfter(now)) {
                    connection.editing = null
                    changed = true
                }
                false
            }
        }
        if (current.isEmpty()) states.remove(scope)
        return changed
    }

    private fun snapshot(scope: Scope): BundlePresenceData {
        val current = states[scope]?.values.orEmpty()
        val members = current.groupBy { it.actor.memberId }.map { (memberId, connections) ->
            BundlePresenceMember(memberId, connections.maxOf { it.lastSignal.plusSeconds(PRESENCE_SECONDS) })
        }.sortedBy { it.memberId }
        val typing = current.mapNotNull { connection ->
            connection.typing?.let { input -> connection to input }
        }.groupBy { (connection, input) -> connection.actor.memberId to input.target }
            .map { (key, inputs) ->
                val (connection, input) = inputs.maxBy { it.second.sequence }
                BundleTypingState(key.first, key.second, input.text, typingExpiry(connection, input))
            }.sortedWith(compareBy<BundleTypingState> { it.memberId }.thenBy { it.target })
        val editing = current.mapNotNull { connection ->
            connection.editing?.let { input -> BundleEditingState(
                connection.actor.memberId, input.target, editingExpiry(connection, input)
            ) }
        }.groupBy { it.memberId to it.target }.values.map { values -> values.maxBy { it.expiresAt } }
            .sortedWith(compareBy<BundleEditingState> { it.memberId }.thenBy { it.target })
        return BundlePresenceData(members, typing, editing)
    }

    private fun editingExpiry(connection: Connection, input: Editing): Instant =
        if (connection.disconnected) minOf(input.at, connection.lastSignal).plusSeconds(PRESENCE_SECONDS)
        else input.at.plusSeconds(PRESENCE_SECONDS)

    private fun validateTarget(target: String?, bundleId: Long, requireExisting: Boolean) {
        if (target in setOf("TITLE", "COMMENT", "DATE", "LOCATION", "PHOTOS", "PARTICIPANTS", "VISIBILITY")) return
        if (target == null || !Regex("(PHOTO|PICK):[1-9][0-9]{0,18}").matches(target)) fail(ErrorCode.INVALID_INPUT)
        val id = target.substringAfter(':').toLongOrNull() ?: fail(ErrorCode.INVALID_INPUT)
        if (requireExisting && photos.findById(id).orElse(null)?.bundleId != bundleId) fail(ErrorCode.RESOURCE_NOT_FOUND)
    }

    private fun typingExpiry(connection: Connection, input: Input): Instant =
        if (connection.disconnected) connection.lastSignal.plusSeconds(PRESENCE_SECONDS)
        else minOf(input.at.plusSeconds(TYPING_SECONDS), connection.lastSignal.plusSeconds(PRESENCE_SECONDS))

    private fun broadcast(scope: Scope, data: BundlePresenceData) {
        try {
            messaging.convertAndSend(
                BundleRealtimeDestinations.topic(scope.bunchId, scope.bundleId),
                data.toMessage(scope.bunchId, scope.bundleId)
            )
        } catch (ex: RuntimeException) {
            logger.warn("Bundle presence delivery failed cause={}", ex.javaClass.simpleName)
        }
    }

    private fun fail(code: ErrorCode): Nothing = throw BusinessException(code)

    private data class Scope(val bunchId: Long, val bundleId: Long)

    private data class Connection(
        val actor: BundleActor,
        var lastSignal: Instant,
        var participant: Boolean,
        var disconnected: Boolean = false,
        var typing: Input? = null,
        var editing: Editing? = null
    )

    private data class Input(val target: String, val text: String, val at: Instant, val sequence: Long)
    private data class Editing(val target: String, val at: Instant)

    private companion object {
        const val MAX_TEXT_BYTES = 256 * 1024
        const val TYPING_SECONDS = 3L
        const val PRESENCE_SECONDS = 15L
    }
}
