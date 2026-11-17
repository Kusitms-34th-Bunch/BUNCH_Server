package com.bunch.member.service

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.member.domain.Member
import com.bunch.member.domain.MemberConsent
import com.bunch.member.domain.MemberConsentRepository
import com.bunch.member.domain.MemberRepository
import com.bunch.member.domain.TermType
import com.bunch.member.dto.AdditionalInfoConsentRequest
import com.bunch.member.dto.AdditionalInfoResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The optional consent to collect and use birth year and gender (7.4.4, 7.4.5). Every agreement and withdrawal is a
 * new row of the history, and the current state is the latest row. The member row is locked first, so two requests
 * of one member (a withdrawal and a profile edit, or two taps) run one after the other.
 */
@Service
class AdditionalInfoService(
    private val members: MemberRepository,
    private val consents: MemberConsentRepository,
    private val terms: TermsService
) {
    @Transactional(readOnly = true)
    fun get(memberId: Long): AdditionalInfoResponse {
        val member = members.findById(memberId)
            .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }
            .requireCompleted()
        return response(member, consented = consenting(member.id))
    }

    // Whether the member agrees now. A caller that changes the demographics asks this inside its own transaction.
    fun consenting(memberId: Long): Boolean = latest(memberId)?.agreed == true

    // The request names the state the member wants. A member who is already there changes nothing (7.4.5 rule 4).
    @Transactional
    fun change(memberId: Long, request: AdditionalInfoConsentRequest): AdditionalInfoResponse {
        val agreed = request.agreed ?: throw BusinessException(ErrorCode.INVALID_INPUT)
        val member = members.findLockedById(memberId)?.requireCompleted()
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)

        if (agreed) agree(member, request.version) else withdraw(member)
        return response(member, consented = agreed)
    }

    // A member who did not agree at signup may agree here (7.4.5 rule 3).
    private fun agree(member: Member, version: String?) {
        val named = version?.takeIf { it.isNotBlank() } ?: throw BusinessException(ErrorCode.INVALID_INPUT)
        if (named != terms.version(TermType.DEMOGRAPHICS)) {
            throw BusinessException(ErrorCode.TERMS_VERSION_MISMATCH)
        }
        if (latest(member.id)?.agreed == true) return

        consents.save(
            MemberConsent(
                memberId = member.id,
                type = TermType.DEMOGRAPHICS,
                version = named,
                agreed = true
            )
        )
    }

    // The values go in the same transaction, and agreeing again does not bring them back (7.4.5 rule 2).
    private fun withdraw(member: Member) {
        val current = latest(member.id)?.takeIf { it.agreed } ?: return

        consents.save(
            MemberConsent(
                memberId = member.id,
                type = TermType.DEMOGRAPHICS,
                version = current.version,
                agreed = false
            )
        )
        member.birthYear = null
        member.gender = null
        members.saveAndFlush(member)
    }

    private fun latest(memberId: Long): MemberConsent? =
        consents.findFirstByMemberIdAndTypeOrderByIdDesc(memberId, TermType.DEMOGRAPHICS)

    // Without the consent the values are not shown, whatever the member row holds.
    private fun response(member: Member, consented: Boolean): AdditionalInfoResponse {
        if (!consented) return AdditionalInfoResponse(false, null, null)
        return AdditionalInfoResponse(true, member.birthYear, member.gender)
    }
}
