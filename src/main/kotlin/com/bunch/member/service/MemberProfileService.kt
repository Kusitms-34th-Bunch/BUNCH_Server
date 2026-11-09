package com.bunch.member.service

import com.bunch.bunch.domain.ProfileImageAction
import com.bunch.global.common.ProfileText
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.image.ProfileImageStorage
import com.bunch.global.image.StoredImage
import com.bunch.image.service.ImageAssetService
import com.bunch.member.domain.Gender
import com.bunch.member.domain.MemberRepository
import com.bunch.member.dto.MemberProfileRequest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.multipart.MultipartFile
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Year
import java.time.ZoneId
import java.util.UUID

@Service
class MemberProfileService(
    private val members: MemberRepository,
    private val images: ProfileImageStorage,
    private val imageAssets: ImageAssetService,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager
) {
    private val transactions = TransactionTemplate(transactionManager)

    // The external upload runs outside the transaction, so this method must not be called inside one.
    fun update(
        memberId: Long,
        requestKey: UUID,
        request: MemberProfileRequest,
        photo: MultipartFile?
    ) {
        val input = normalize(request, photo)
        if (isReplay(memberId, requestKey, input)) return

        var uploaded: StoredImage? = null
        var attached = false
        try {
            if (input.imageAction == ProfileImageAction.REPLACE) {
                uploaded = images.upload(requireNotNull(photo))
            }
            val stored = uploaded
            attached = transactions.execute { save(memberId, requestKey, input, stored) }!!
        } catch (ex: DataIntegrityViolationException) {
            if (members.existsByNicknameAndIdNot(input.nickname, memberId)) fail(ErrorCode.NICKNAME_TAKEN)
            throw ex
        } finally {
            // A file that was uploaded but not attached to the member is never kept.
            if (!attached) images.delete(uploaded?.publicId)
        }
    }

    private fun normalize(request: MemberProfileRequest, photo: MultipartFile?): Input {
        val nickname = ProfileText.name(request.nickname)
        val latestBirthYear = Year.now(clock.withZone(SEOUL)).value - MINIMUM_AGE
        if (request.birthYear != null && request.birthYear !in EARLIEST_BIRTH_YEAR..latestBirthYear) {
            fail(ErrorCode.INVALID_INPUT)
        }
        when (request.imageAction) {
            ProfileImageAction.KEEP, ProfileImageAction.RESET -> if (photo != null) fail(ErrorCode.INVALID_INPUT)
            ProfileImageAction.REPLACE -> if (photo == null || photo.isEmpty) fail(ErrorCode.INVALID_INPUT)
        }
        val photoHash = if (request.imageAction == ProfileImageAction.REPLACE) {
            hashOf(requireNotNull(photo))
        } else {
            "-"
        }
        val fingerprint = listOf(
            nickname,
            request.birthYear?.toString() ?: "-",
            request.gender?.name ?: "-",
            request.imageAction.name,
            photoHash
        ).joinToString("") { "${it.length}:$it" }
        return Input(
            nickname = nickname,
            birthYear = request.birthYear,
            gender = request.gender,
            imageAction = request.imageAction,
            fingerprint = digest(fingerprint.toByteArray(Charsets.UTF_8))
        )
    }

    // Answers a retried request before any upload, and rejects an account that cannot edit its profile.
    private fun isReplay(memberId: Long, requestKey: UUID, input: Input): Boolean {
        val member = members.findById(memberId).orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }
        member.requireCompleted()
        if (member.profileRequestKey != requestKey) return false
        if (member.profileRequestFingerprint != input.fingerprint) fail(ErrorCode.IDEMPOTENCY_CONFLICT)
        return true
    }

    // Returns true when the uploaded image became the profile image of the member.
    private fun save(memberId: Long, requestKey: UUID, input: Input, uploaded: StoredImage?): Boolean {
        val member = members.findLockedById(memberId)?.requireCompleted()
            ?: fail(ErrorCode.USER_NOT_FOUND)
        if (member.profileRequestKey == requestKey) {
            if (member.profileRequestFingerprint != input.fingerprint) fail(ErrorCode.IDEMPOTENCY_CONFLICT)
            return false
        }
        if (members.existsByNicknameAndIdNot(input.nickname, memberId)) fail(ErrorCode.NICKNAME_TAKEN)

        val previousAssetId = member.profileImageAsset?.id
        val newAsset = when (input.imageAction) {
            ProfileImageAction.KEEP -> member.profileImageAsset
            ProfileImageAction.RESET -> null
            ProfileImageAction.REPLACE -> imageAssets.store(requireNotNull(uploaded))
        }
        member.nickname = input.nickname
        member.birthYear = input.birthYear
        member.gender = input.gender
        member.profileImageAsset = newAsset
        member.profileRequestKey = requestKey
        member.profileRequestFingerprint = input.fingerprint
        imageAssets.releaseAfterCommit(previousAssetId.takeUnless { it == newAsset?.id })
        members.saveAndFlush(member)
        return input.imageAction == ProfileImageAction.REPLACE
    }

    private fun hashOf(photo: MultipartFile): String {
        val digest = MessageDigest.getInstance("SHA-256")
        photo.inputStream.use { it.transferTo(DigestOutputStream(OutputStream.nullOutputStream(), digest)) }
        return digest.digest().toHex()
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun fail(code: ErrorCode): Nothing = throw BusinessException(code)

    private data class Input(
        val nickname: String,
        val birthYear: Int?,
        val gender: Gender?,
        val imageAction: ProfileImageAction,
        val fingerprint: String
    )

    private companion object {
        const val EARLIEST_BIRTH_YEAR = 1930
        const val MINIMUM_AGE = 14
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
