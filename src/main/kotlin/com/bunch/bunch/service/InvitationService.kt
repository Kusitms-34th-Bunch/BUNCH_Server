package com.bunch.bunch.service

import com.bunch.bunch.domain.*
import com.bunch.bunch.dto.*
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.member.domain.MemberRepository
import com.bunch.notification.domain.JoinNotificationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

@Service
class InvitationService(
    private val bunches: BunchRepository,
    private val invitations: InvitationRepository,
    private val members: MemberRepository,
    private val notifications: JoinNotificationRepository,
    private val input: InvitationInput,
    private val rateLimiter: InvitationRateLimiter
) {
    private val random = SecureRandom()

    @Transactional
    fun issue(userId: Long, bunchId: Long): InvitationResponse {
        completed(userId)
        bunches.findLocked(bunchId) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
        bunches.activeMembership(userId, bunchId) ?: fail(ErrorCode.FORBIDDEN)
        val now = Instant.now()
        invitations.findNonrevoked(bunchId)?.takeIf { it.isValid(now) }?.let { return response(it) }
        invitations.revoke(bunchId, now)
        repeat(10) {
            val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
            val code = (1..8).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("")
            invitations.issue(bunchId, userId, token, code, now, now.plus(Duration.ofDays(7)))?.let { return response(it) }
        }
        fail(ErrorCode.INTERNAL_ERROR)
    }

    @Transactional(readOnly = true)
    fun publicPreview(invitation: String, remoteAddress: String): InvitationCard {
        rateLimiter.publicRequest(remoteAddress)
        val resolved = input.resolve(invitation)?.takeIf { it.isValid(Instant.now()) } ?: fail(ErrorCode.INVALID_INVITATION)
        return card(resolved)
    }

    @Transactional(readOnly = true)
    fun verify(userId: Long, invitation: String): InvitationVerification {
        completed(userId)
        val resolved = input.resolve(invitation)?.takeIf { it.isValid(Instant.now()) } ?: rateLimiter.invalidInvitation(userId)
        return InvitationVerification(card(resolved), eligibility(userId, resolved.bunchId))
    }

    @Transactional
    fun join(userId: Long, key: UUID, invitation: String): JoinResponse {
        members.findLockedById(userId)?.requireCompleted() ?: fail(ErrorCode.USER_NOT_FOUND)
        val resolved = input.resolve(invitation)
        bunches.findJoinReplay(userId, key)?.let { replay ->
            if (resolved == null || replay.fingerprint != fingerprint(resolved)) fail(ErrorCode.IDEMPOTENCY_CONFLICT)
            val bunch = bunches.findLocked(replay.bunchId) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
            bunches.activeMembership(userId, bunch.id)?.let { bunches.markUsed(it.id, Instant.now()) }
            return JoinResponse(replay.outcome, BunchResponse.from(bunch))
        }
        if (resolved == null) rateLimiter.invalidInvitation(userId)
        val bunch = bunches.findLocked(resolved.bunchId) ?: rateLimiter.invalidInvitation(userId)
        // Invitation rotation uses this same bunch lock. Re-read after acquiring it.
        val current = invitations.findById(resolved.id)?.takeIf { it.isValid(Instant.now()) }
            ?: rateLimiter.invalidInvitation(userId)
        val now = Instant.now()
        val existing = bunches.activeMembership(userId, bunch.id)
        if (existing != null) {
            bunches.markUsed(existing.id, now)
            bunches.rememberJoin(existing.id, key, fingerprint(current), JoinOutcome.ALREADY_MEMBER)
            return JoinResponse(JoinOutcome.ALREADY_MEMBER, BunchResponse.from(bunch))
        }
        if (bunches.countMembers(bunch.id) >= 8) fail(ErrorCode.BUNCH_FULL)
        if (bunches.countAffiliations(userId) >= 10) fail(ErrorCode.MEMBERSHIP_LIMIT_EXCEEDED)
        val membership = bunches.addMembership(userId, bunch.id, MembershipRole.MEMBER, now)
        bunches.rememberJoin(membership.id, key, fingerprint(current), JoinOutcome.JOINED)
        notifications.notifyExistingMembers(bunch.id, membership.id, userId, now)
        return JoinResponse(JoinOutcome.JOINED, BunchResponse.from(bunch))
    }

    private fun eligibility(userId: Long, bunchId: Long): InviteEligibility = when {
        bunches.activeMembership(userId, bunchId) != null -> InviteEligibility.ALREADY_MEMBER
        bunches.countMembers(bunchId) >= 8 -> InviteEligibility.FULL
        bunches.countAffiliations(userId) >= 10 -> InviteEligibility.LIMIT_EXCEEDED
        else -> InviteEligibility.AVAILABLE
    }

    private fun card(invitation: Invitation): InvitationCard {
        val bunch = bunches.find(invitation.bunchId) ?: fail(ErrorCode.INVALID_INVITATION)
        return InvitationCard(bunch.name, bunch.introduction, bunch.imageUrl, invitations.inviterNickname(invitation), invitation.code)
    }

    private fun completed(userId: Long) = members.findById(userId)
        .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }.requireCompleted()
    private fun fingerprint(invitation: Invitation): String = "invitation:${invitation.id}"
    private fun response(invitation: Invitation) = InvitationResponse(input.url(invitation.token), invitation.code, invitation.expiresAt)
    private fun fail(code: ErrorCode): Nothing = throw BusinessException(code)

    companion object { private const val CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ" }
}
