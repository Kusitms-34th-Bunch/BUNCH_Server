package com.bunch.member.service

import com.bunch.bunch.domain.ProfileImageAction
import com.bunch.global.common.ProfileText
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.image.ProfileImageStorage
import com.bunch.global.image.StoredImage
import com.bunch.global.profanity.ProfanityFilter
import com.bunch.image.service.ImageAssetService
import com.bunch.member.domain.BirthYearRule
import com.bunch.member.domain.MemberRepository
import com.bunch.member.dto.MemberProfileRequest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.multipart.MultipartFile

@Service
class MemberProfileService(
    private val members: MemberRepository,
    private val images: ProfileImageStorage,
    private val imageAssets: ImageAssetService,
    private val profanity: ProfanityFilter,
    private val additionalInfos: AdditionalInfoService,
    transactionManager: PlatformTransactionManager
) {
    private val transactions = TransactionTemplate(transactionManager)

    // The request carries the final state of the screen, so a retried request ends in the same state.
    // The external upload runs outside the transaction, so this method must not be called inside one.
    fun update(memberId: Long, request: MemberProfileRequest, photo: MultipartFile?) {
        val input = normalize(request, photo)
        requireEditable(memberId)
        profanity.requireClean(mapOf("nickname" to input.nickname))

        var uploaded: StoredImage? = null
        var attached = false
        try {
            if (input.imageAction == ProfileImageAction.REPLACE) {
                uploaded = images.upload(requireNotNull(photo))
            }
            val stored = uploaded
            attached = transactions.execute { save(memberId, input, stored) }!!
        } catch (ex: DataIntegrityViolationException) {
            if (members.existsByNicknameAndIdNot(input.nickname, memberId)) fail(ErrorCode.NICKNAME_TAKEN)
            throw ex
        } finally {
            // A file that was uploaded but not attached to the member is never kept.
            if (!attached) images.delete(uploaded?.objectKey)
        }
    }

    // Returns the request with the nickname in the stored form.
    private fun normalize(request: MemberProfileRequest, photo: MultipartFile?): MemberProfileRequest {
        when (request.imageAction) {
            ProfileImageAction.KEEP, ProfileImageAction.RESET -> if (photo != null) fail(ErrorCode.INVALID_INPUT)
            ProfileImageAction.REPLACE -> if (photo == null || photo.isEmpty) fail(ErrorCode.INVALID_INPUT)
        }
        // The birth year follows the signup rule (1.4.5). The gender is an enum, so an unknown one never gets here.
        val birthYear = request.demographics?.birthYear
        if (birthYear != null && !BirthYearRule.isValid(birthYear)) fail(ErrorCode.INVALID_INPUT)
        return request.copy(nickname = ProfileText.name(request.nickname))
    }

    // Rejects an account that cannot edit its profile before anything is uploaded.
    private fun requireEditable(memberId: Long) {
        members.findById(memberId).orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }
            .requireCompleted()
    }

    // Returns true when the uploaded image became the profile image of the member.
    private fun save(memberId: Long, input: MemberProfileRequest, uploaded: StoredImage?): Boolean {
        val member = members.findLockedById(memberId)?.requireCompleted()
            ?: fail(ErrorCode.USER_NOT_FOUND)
        // The consent is read under the member lock, so a withdrawal from another device is seen here (7.2.6).
        val demographics = input.demographics
        if (demographics != null && !additionalInfos.consenting(memberId)) fail(ErrorCode.FORBIDDEN)
        profanity.requireClean(mapOf("nickname" to input.nickname))
        if (members.existsByNicknameAndIdNot(input.nickname, memberId)) fail(ErrorCode.NICKNAME_TAKEN)

        val previousAssetId = member.profileImageAsset?.id
        val newAsset = when (input.imageAction) {
            ProfileImageAction.KEEP -> member.profileImageAsset
            ProfileImageAction.RESET -> null
            ProfileImageAction.REPLACE -> imageAssets.store(requireNotNull(uploaded))
        }
        member.nickname = input.nickname
        member.profileImageAsset = newAsset
        if (demographics != null) {
            member.birthYear = demographics.birthYear
            member.gender = demographics.gender
        }
        imageAssets.releaseAfterCommit(previousAssetId.takeUnless { it == newAsset?.id })
        members.saveAndFlush(member)
        return input.imageAction == ProfileImageAction.REPLACE
    }

    private fun fail(code: ErrorCode): Nothing = throw BusinessException(code)
}
