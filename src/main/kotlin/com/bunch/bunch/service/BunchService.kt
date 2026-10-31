package com.bunch.bunch.service

import com.bunch.bunch.domain.*
import com.bunch.bunch.dto.*
import com.bunch.global.common.ProfileText
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.image.ProfileImageStorage
import com.bunch.image.service.ImageAssetService
import com.bunch.member.domain.MemberRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.multipart.MultipartFile
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@Service
class BunchService(
    private val bunches: BunchRepository,
    private val memberships: BunchMembershipRepository,
    private val members: MemberRepository,
    private val images: ProfileImageStorage,
    private val imageAssets: ImageAssetService,
    transactionManager: PlatformTransactionManager
) {
    private val transactions = TransactionTemplate(transactionManager)

    @Transactional(readOnly = true)
    fun nameAvailability(userId: Long, name: String): NameAvailability {
        completed(userId)
        val normalized = ProfileText.name(name)
        return NameAvailability(normalized, !bunches.existsByName(normalized))
    }

    @Transactional
    fun create(userId: Long, key: UUID, request: CreateBunchRequest): BunchResponse {
        val member = members
            .findLockedById(userId)
            ?.requireCompleted()
            ?: fail(ErrorCode.USER_NOT_FOUND)

        val name = ProfileText.name(request.name)
        val introduction = ProfileText.introduction(request.introduction)
        val fingerprint = fingerprint(name, introduction, request.visibility.name, request.characterType.name)
        val now = Instant.now()

        bunches.findByCreatedByAndCreationRequestKey(userId, key)?.let { previous ->
            if (previous.creationFingerprint != fingerprint) fail(ErrorCode.IDEMPOTENCY_CONFLICT)
            memberships.findByMemberIdAndBunchIdAndStatus(userId, previous.id)?.let { it.lastUsedAt = now }
            return BunchResponse.from(previous)
        }

        if (memberships.countByMemberIdAndStatus(userId) >= 10) fail(ErrorCode.MEMBERSHIP_LIMIT_EXCEEDED)
        if (
            bunches.insertIfNameAvailable(
                userId,
                key,
                fingerprint,
                name,
                introduction,
                request.visibility.name,
                request.characterType.name,
                now
            ) == 0
        )
            fail(ErrorCode.BUNCH_NAME_TAKEN)

        val bunch = bunches.findByCreatedByAndCreationRequestKey(userId, key)!!
        memberships.save(
            BunchMembership(
                bunch = bunch,
                member = member,
                role = MembershipRole.OWNER,
                joinedAt = now
            )
        )

        return BunchResponse.from(bunch)
    }

    @Transactional(readOnly = true)
    fun defaultBunchId(userId: Long): Long? =
        memberships.findFirstByMemberIdAndStatusOrderByLastUsedAtDescIdDesc(userId)?.bunch?.id

    @Transactional(readOnly = true)
    fun defaultBunch(userId: Long): BunchResponse? =
        memberships.findFirstByMemberIdAndStatusOrderByLastUsedAtDescIdDesc(userId)
            ?.let { BunchResponse.from(it.bunch) }

    // A read-only check: the write paths below lock the bunch, but listing members changes nothing.
    @Transactional(readOnly = true)
    fun members(userId: Long, bunchId: Long): List<BunchMemberResponse> {
        completed(userId)
        if (!bunches.existsById(bunchId)) fail(ErrorCode.RESOURCE_NOT_FOUND)
        memberships.findByMemberIdAndBunchIdAndStatus(userId, bunchId) ?: fail(ErrorCode.FORBIDDEN)
        return memberships.findActiveMembers(bunchId).map(BunchMemberResponse::from)
    }

    @Transactional
    fun updateLastUsed(userId: Long, bunchId: Long) {
        lockOwnerOrMember(userId, bunchId, ownerOnly = false)
        val membership = memberships.findByMemberIdAndBunchIdAndStatus(userId, bunchId) ?: fail(ErrorCode.FORBIDDEN)
        membership.lastUsedAt = Instant.now()
    }

    // Preflight avoids uploading for unauthorized callers. The second check protects against
    // membership changes while the external upload is in flight, without holding database locks.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun updateProfileImage(userId: Long, bunchId: Long, photo: MultipartFile): BunchResponse {
        transactions.execute { lockOwnerOrMember(userId, bunchId, ownerOnly = true) }
        val uploaded = images.upload(photo)
        val result = try {
            transactions.execute {
                val current = lockOwnerOrMember(userId, bunchId, ownerOnly = true)
                val previousAssetId = current.profileImageAsset?.id
                current.profileImageAsset = imageAssets.store(uploaded)
                imageAssets.releaseAfterCommit(previousAssetId)
                bunches.flush()
                BunchResponse.from(current)
            }!!
        } catch (ex: Exception) {
            images.delete(uploaded.publicId)
            throw ex
        }
        return result
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun resetProfileImage(userId: Long, bunchId: Long): BunchResponse {
        val result = transactions.execute {
            val current = lockOwnerOrMember(userId, bunchId, ownerOnly = true)
            val previousAssetId = current.profileImageAsset?.id
            current.profileImageAsset = null
            imageAssets.releaseAfterCommit(previousAssetId)
            bunches.flush()
            BunchResponse.from(current)
        }!!
        return result
    }

    private fun completed(userId: Long) = members
        .findById(userId)
        .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }
        .requireCompleted()

    private fun lockOwnerOrMember(userId: Long, bunchId: Long, ownerOnly: Boolean): Bunch {
        members.findLockedById(userId)?.requireCompleted() ?: fail(ErrorCode.USER_NOT_FOUND)
        val bunch = bunches.findLocked(bunchId) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
        val membership = memberships.findByMemberIdAndBunchIdAndStatus(userId, bunchId) ?: fail(ErrorCode.FORBIDDEN)
        if (ownerOnly && membership.role != MembershipRole.OWNER) fail(ErrorCode.FORBIDDEN)
        return bunch
    }

    private fun fingerprint(vararg values: String): String = MessageDigest.getInstance("SHA-256")
        .digest(
            values.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
        )
        .joinToString("") { "%02x".format(it) }

    private fun fail(code: ErrorCode): Nothing = throw BusinessException(code)
}
