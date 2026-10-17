package com.bunch.bunch.service

import com.bunch.bunch.domain.*
import com.bunch.bunch.dto.*
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.member.domain.MemberRepository
import com.bunch.notification.domain.JoinNotification
import com.bunch.notification.domain.JoinNotificationRepository
import jakarta.persistence.EntityManager
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
    private val memberships: BunchMembershipRepository,
    private val invitations: InvitationRepository,
    private val members: MemberRepository,
    private val notifications: JoinNotificationRepository,
    private val input: InvitationInput,
    private val rateLimiter: InvitationRateLimiter,
    private val entityManager: EntityManager
) {
    private val random = SecureRandom()

    @Transactional
    fun issue(userId: Long, bunchId: Long): InvitationResponse {
        members.findLockedById(userId)?.requireCompleted() ?: fail(ErrorCode.USER_NOT_FOUND)
        bunches.findLocked(bunchId) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
        memberships.findByMemberIdAndBunchIdAndStatus(userId, bunchId) ?: fail(ErrorCode.FORBIDDEN)
        val now = Instant.now()
        invitations.findByBunchIdAndRevokedAtIsNull(bunchId)?.let {
            if (it.isValid(now)) return response(it)
            it.revokedAt = now
        }
        repeat(10) {
            val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
            val code = (1..8).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("")
            if (invitations.insertIfAvailable(bunchId, userId, token, code, now, now.plus(Duration.ofDays(7))) == 1) {
                return response(invitations.findByToken(token)!!)
            }
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
        val member = members.findLockedById(userId)?.requireCompleted() ?: fail(ErrorCode.USER_NOT_FOUND)
        val resolved = input.resolve(invitation)
        memberships.findJoinReplay(userId, key.toString())?.let { previous ->
            val replay = previous.joinRequests.getValue(key.toString())
            if (resolved == null || replay.fingerprint != fingerprint(resolved)) fail(ErrorCode.IDEMPOTENCY_CONFLICT)
            val bunch = bunches.findLocked(previous.bunch.id) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
            memberships.findByMemberIdAndBunchIdAndStatus(userId, bunch.id)?.let { it.lastUsedAt = Instant.now() }
            return JoinResponse(replay.outcome, BunchResponse.from(bunch))
        }
        if (resolved == null) rateLimiter.invalidInvitation(userId)
        val bunch = bunches.findLocked(resolved.bunchId) ?: rateLimiter.invalidInvitation(userId)
        // Invitation rotation uses this same bunch lock. Re-read after acquiring it.
        entityManager.refresh(resolved)
        val current = resolved.takeIf { it.isValid(Instant.now()) } ?: rateLimiter.invalidInvitation(userId)
        val now = Instant.now()
        val existing = memberships.findByMemberIdAndBunchIdAndStatus(userId, bunch.id)
        if (existing != null) {
            existing.lastUsedAt = now
            existing.rememberJoin(key, fingerprint(current), JoinOutcome.ALREADY_MEMBER)
            return JoinResponse(JoinOutcome.ALREADY_MEMBER, BunchResponse.from(bunch))
        }
        if (memberships.countByBunchIdAndStatus(bunch.id) >= 8) fail(ErrorCode.BUNCH_FULL)
        if (memberships.countByMemberIdAndStatus(userId) >= 10) fail(ErrorCode.MEMBERSHIP_LIMIT_EXCEEDED)
        val membership = memberships.save(BunchMembership(bunch = bunch, member = member, role = MembershipRole.MEMBER, joinedAt = now))
        membership.rememberJoin(key, fingerprint(current), JoinOutcome.JOINED)
        val recipients = memberships.findAllByBunchIdAndStatus(bunch.id).filter { it.member.id != userId }
        notifications.saveAll(recipients.map {
            JoinNotification(bunchId = bunch.id, joinedMembershipId = membership.id,
                recipientMemberId = it.member.id, joinedMemberId = userId, createdAt = now)
        })
        return JoinResponse(JoinOutcome.JOINED, BunchResponse.from(bunch))
    }

    private fun eligibility(userId: Long, bunchId: Long): InviteEligibility = when {
        memberships.findByMemberIdAndBunchIdAndStatus(userId, bunchId) != null -> InviteEligibility.ALREADY_MEMBER
        memberships.countByBunchIdAndStatus(bunchId) >= 8 -> InviteEligibility.FULL
        memberships.countByMemberIdAndStatus(userId) >= 10 -> InviteEligibility.LIMIT_EXCEEDED
        else -> InviteEligibility.AVAILABLE
    }

    private fun card(invitation: Invitation): InvitationCard {
        val bunch = bunches.findById(invitation.bunchId).orElse(null) ?: fail(ErrorCode.INVALID_INVITATION)
        return InvitationCard(bunch.name, bunch.introduction, bunch.imageUrl, memberships.inviterNickname(invitation.issuerMemberId, invitation.bunchId), invitation.code)
    }

    private fun completed(userId: Long) = members.findById(userId)
        .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }.requireCompleted()
    private fun fingerprint(invitation: Invitation): String = "invitation:${invitation.id}"
    private fun response(invitation: Invitation) = InvitationResponse(input.url(invitation.token), invitation.code, invitation.expiresAt)
    private fun fail(code: ErrorCode): Nothing = throw BusinessException(code)

    companion object { private const val CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ" }
}
