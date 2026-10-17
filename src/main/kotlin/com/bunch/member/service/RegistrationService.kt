package com.bunch.member.service

import com.bunch.global.common.ProfileText
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.image.ProfileImageStorage
import com.bunch.global.image.StoredImage
import com.bunch.member.domain.*
import com.bunch.member.dto.*
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.multipart.MultipartFile
import java.security.MessageDigest
import java.sql.SQLException
import java.time.Instant
import java.time.Year
import java.time.ZoneId

@Service
class RegistrationService(
    private val members: MemberRepository,
    private val consents: MemberConsentRepository,
    private val terms: TermsService,
    private val images: ProfileImageStorage,
    transactionManager: PlatformTransactionManager
) {
    private val transaction = TransactionTemplate(transactionManager)

    fun nicknameAvailable(memberId: Long, nickname: String): NameAvailability {
        members.findById(memberId).orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }.requireActive()
        val name = ProfileText.name(nickname)
        return NameAvailability(name, !members.existsByNickname(name))
    }

    fun complete(memberId: Long, request: RegistrationRequest, photo: MultipartFile?): RegistrationResponse {
        val nickname = ProfileText.name(request.nickname)
        val lastYear = Year.now(ZoneId.of("Asia/Seoul")).value - 14
        if (request.birthYear != null && request.birthYear !in 1950..lastYear) invalid()
        if (request.demographicsConsentVersion == null && (request.birthYear != null || request.gender != null)) invalid()
        val photoHash = photo?.takeIf { !it.isEmpty }?.let {
            if (it.size <= 5L * 1024 * 1024) digest(it.bytes) else "oversize:${it.size}"
        }
        val fingerprint = digest("$nickname|${request.birthYear}|${request.gender}|${request.demographicsConsentVersion}|$photoHash".toByteArray())
        var uploaded: StoredImage? = null
        try {
            return transaction.execute {
                val member = members.findLockedById(memberId)?.requireActive()
                    ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
                if (member.signupStatus == SignupStatus.COMPLETED) {
                    if (member.registrationFingerprint != fingerprint) throw BusinessException(ErrorCode.REGISTRATION_CONFLICT)
                    return@execute response(member)
                }
                if (!terms.accepted(memberId)) throw BusinessException(ErrorCode.SIGNUP_INCOMPLETE)
                if (request.demographicsConsentVersion != null && request.demographicsConsentVersion != terms.version(TermType.DEMOGRAPHICS)) {
                    throw BusinessException(ErrorCode.TERMS_VERSION_MISMATCH)
                }
                if (members.existsByNickname(nickname)) throw BusinessException(ErrorCode.NICKNAME_TAKEN)
                uploaded = photo?.takeIf { !it.isEmpty }?.let {
                    try { images.upload(it) } catch (_: BusinessException) { null }
                }
                member.nickname = nickname
                member.birthYear = request.birthYear
                member.gender = request.gender
                member.imagePublicId = uploaded?.publicId
                member.imageUrl = uploaded?.url
                member.signupStatus = SignupStatus.COMPLETED
                member.completedAt = Instant.now()
                member.registrationFingerprint = fingerprint
                if (request.demographicsConsentVersion != null) {
                    consents.save(MemberConsent(memberId = memberId, type = TermType.DEMOGRAPHICS, version = request.demographicsConsentVersion))
                }
                members.saveAndFlush(member)
                response(member)
            }!!
        } catch (e: RuntimeException) {
            images.delete(uploaded?.publicId)
            if (e is DataIntegrityViolationException && (e.mostSpecificCause as? SQLException)?.sqlState == "23505") {
                throw BusinessException(ErrorCode.NICKNAME_TAKEN)
            }
            throw e
        }
    }

    private fun response(member: Member) = RegistrationResponse(member.id, member.signupStatus, member.nickname!!, member.imageUrl)
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun invalid(): Nothing = throw BusinessException(ErrorCode.INVALID_INPUT)
}
