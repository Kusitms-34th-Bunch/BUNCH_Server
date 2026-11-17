package com.bunch.member

import com.bunch.member.domain.MemberConsent
import com.bunch.member.domain.MemberConsentRepository
import com.bunch.member.domain.TermType

// The optional consent as a signup leaves it: the member agreed to this version of the text.
fun MemberConsentRepository.agreeToDemographics(memberId: Long, version: String = "v1"): MemberConsent = save(
    MemberConsent(
        memberId = memberId,
        type = TermType.DEMOGRAPHICS,
        version = version,
        agreed = true
    )
)
