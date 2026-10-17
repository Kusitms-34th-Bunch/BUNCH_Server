package com.bunch.bunch.domain

import java.time.Instant

enum class Visibility { PUBLIC, PRIVATE }
enum class CharacterType { TYPE_1, TYPE_2, TYPE_3, TYPE_4 }
enum class MembershipRole { OWNER, MEMBER }
enum class MembershipStatus { ACTIVE, LEFT, REMOVED }
enum class InviteEligibility { AVAILABLE, ALREADY_MEMBER, FULL, LIMIT_EXCEEDED }
enum class JoinOutcome { JOINED, ALREADY_MEMBER }

data class Bunch(
    val id: Long,
    val name: String,
    val introduction: String,
    val visibility: Visibility,
    val characterType: CharacterType,
    val imagePublicId: String?,
    val imageUrl: String?,
    val creationFingerprint: String
)

data class Membership(val id: Long, val bunchId: Long, val memberId: Long, val role: MembershipRole, val status: MembershipStatus)

data class Invitation(
    val id: Long,
    val bunchId: Long,
    val issuerMemberId: Long,
    val token: String,
    val code: String,
    val expiresAt: Instant,
    val revokedAt: Instant?
) {
    fun isValid(now: Instant): Boolean = revokedAt == null && expiresAt.isAfter(now)
}

data class JoinReplay(val membershipId: Long, val bunchId: Long, val fingerprint: String, val outcome: JoinOutcome)
