package com.bunch.member.service

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.member.domain.*
import com.bunch.member.dto.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TermsService(
    private val members: MemberRepository,
    private val consents: MemberConsentRepository,
    @Value("\${terms.service-version:}") serviceVersion: String,
    @Value("\${terms.privacy-version:}") privacyVersion: String,
    @Value("\${terms.age-version:}") ageVersion: String,
    @Value("\${terms.demographics-version:}") demographicsVersion: String
) {
    private val versions = mapOf(
        TermType.SERVICE to serviceVersion,
        TermType.PRIVACY to privacyVersion,
        TermType.AGE_OVER_14 to ageVersion,
        TermType.DEMOGRAPHICS to demographicsVersion
    )

    fun current() = TermsResponse(TermType.entries.map { TermVersion(it, version(it), it.required) })

    fun version(type: TermType): String = versions.getValue(type).takeIf { it.isNotBlank() }
        ?: throw BusinessException(ErrorCode.TERMS_UNAVAILABLE)

    fun accepted(memberId: Long): Boolean {
        val accepted = consents.findAllByMemberId(memberId).map { it.type }.toSet()
        return TermType.entries
            .filter { it.required }
            .all { it in accepted }
    }

    @Transactional
    fun agree(memberId: Long, request: TermsRequest) {
        val member = members.findLockedById(memberId)?.requireActive()
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
        if (member.signupStatus == SignupStatus.COMPLETED) throw BusinessException(ErrorCode.FORBIDDEN)
        val required = TermType.entries.filter { it.required }.toSet()
        if (
            request.agreements.size != required.size ||
                request.agreements.map { it.type }.toSet() != required ||
                request.agreements.any { !it.agreed }
        )
            throw BusinessException(ErrorCode.INVALID_INPUT)
        if (request.agreements.any { it.version != version(it.type) }) {
            throw BusinessException(ErrorCode.TERMS_VERSION_MISMATCH)
        }
        val previous = consents.findAllByMemberId(memberId)
        request.agreements
            .filter { agreement -> previous.none { it.type == agreement.type && it.version == agreement.version } }
            .forEach {
                consents.save(
                    MemberConsent(
                        memberId = memberId,
                        type = it.type,
                        version = it.version
                    )
                )
            }
    }
}
