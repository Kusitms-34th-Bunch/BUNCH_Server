package com.bunch.bunch.service

import com.bunch.bunch.domain.*
import com.bunch.bunch.dto.*
import com.bunch.global.common.ProfileText
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.image.ProfileImageStorage
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
    private val members: MemberRepository,
    private val images: ProfileImageStorage,
    transactionManager: PlatformTransactionManager
) {
    private val transactions = TransactionTemplate(transactionManager)

    @Transactional(readOnly = true)
    fun nameAvailability(userId: Long, name: String): NameAvailability {
        completed(userId)
        val normalized = ProfileText.name(name)
        return NameAvailability(normalized, !bunches.nameExists(normalized))
    }

    @Transactional
    fun create(userId: Long, key: UUID, request: CreateBunchRequest): BunchResponse {
        members.findLockedById(userId)?.requireCompleted() ?: fail(ErrorCode.USER_NOT_FOUND)
        val name = ProfileText.name(request.name)
        val introduction = ProfileText.introduction(request.introduction)
        val fingerprint = fingerprint(name, introduction, request.visibility.name, request.characterType.name)
        val now = Instant.now()
        bunches.findCreation(userId, key)?.let { previous ->
            if (previous.creationFingerprint != fingerprint) fail(ErrorCode.IDEMPOTENCY_CONFLICT)
            val current = bunches.findLocked(previous.id) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
            bunches.activeMembership(userId, current.id)?.let { bunches.markUsed(it.id, now) }
            return BunchResponse.from(current)
        }
        if (bunches.countAffiliations(userId) >= 10) fail(ErrorCode.MEMBERSHIP_LIMIT_EXCEEDED)
        val bunch = bunches.create(userId, key, fingerprint, name, introduction, request.visibility, request.characterType, now)
            ?: fail(ErrorCode.BUNCH_NAME_TAKEN)
        bunches.addMembership(userId, bunch.id, MembershipRole.OWNER, now)
        return BunchResponse.from(bunch)
    }

    @Transactional(readOnly = true)
    fun defaultBunchId(userId: Long): Long? = bunches.defaultBunchId(userId)

    @Transactional
    fun updateLastUsed(userId: Long, bunchId: Long) {
        lockOwnerOrMember(userId, bunchId, ownerOnly = false)
        val membership = bunches.activeMembership(userId, bunchId) ?: fail(ErrorCode.FORBIDDEN)
        bunches.markUsed(membership.id, Instant.now())
    }

    // Preflight avoids uploading for unauthorized callers. The second check protects against
    // membership changes while the external upload is in flight, without holding database locks.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun updateProfileImage(userId: Long, bunchId: Long, photo: MultipartFile): BunchResponse {
        transactions.execute { lockOwnerOrMember(userId, bunchId, ownerOnly = true) }
        val uploaded = images.upload(photo)
        var previousPublicId: String? = null
        val result = try {
            transactions.execute {
                val current = lockOwnerOrMember(userId, bunchId, ownerOnly = true)
                previousPublicId = current.imagePublicId
                bunches.updateImage(bunchId, uploaded.publicId, uploaded.url)
                BunchResponse.from(current.copy(imagePublicId = uploaded.publicId, imageUrl = uploaded.url))
            }!!
        } catch (ex: Exception) {
            images.delete(uploaded.publicId)
            throw ex
        }
        images.delete(previousPublicId)
        return result
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun resetProfileImage(userId: Long, bunchId: Long): BunchResponse {
        var previousPublicId: String? = null
        val result = transactions.execute {
            val current = lockOwnerOrMember(userId, bunchId, ownerOnly = true)
            previousPublicId = current.imagePublicId
            bunches.updateImage(bunchId, null, null)
            BunchResponse.from(current.copy(imagePublicId = null, imageUrl = null))
        }!!
        images.delete(previousPublicId)
        return result
    }

    private fun completed(userId: Long) = members.findById(userId).orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }.requireCompleted()

    private fun lockOwnerOrMember(userId: Long, bunchId: Long, ownerOnly: Boolean): Bunch {
        members.findLockedById(userId)?.requireCompleted() ?: fail(ErrorCode.USER_NOT_FOUND)
        val bunch = bunches.findLocked(bunchId) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
        val membership = bunches.activeMembership(userId, bunchId) ?: fail(ErrorCode.FORBIDDEN)
        if (ownerOnly && membership.role != MembershipRole.OWNER) fail(ErrorCode.FORBIDDEN)
        return bunch
    }

    private fun fingerprint(vararg values: String): String = MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun fail(code: ErrorCode): Nothing = throw BusinessException(code)
}
