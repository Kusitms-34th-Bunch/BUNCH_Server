package com.bunch.member.dto

import com.bunch.member.domain.Gender
import com.bunch.member.domain.SignupStatus
import com.bunch.member.domain.TermType
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank

data class TermsResponse(val terms: List<TermVersion>)

data class TermVersion(
    val type: TermType,
    val version: String,
    val required: Boolean
)

data class TermsRequest(
    @field:Valid
    val agreements: List<TermAgreement>
)

data class TermAgreement(
    val type: TermType,
    @field:NotBlank
    val version: String,
    val agreed: Boolean
)

data class RegistrationRequest(
    @field:NotBlank
    val nickname: String,

    val birthYear: Int? = null,
    val gender: Gender? = null,
    val demographicsConsentVersion: String? = null
)

data class RegistrationResponse(
    val memberId: Long,
    val signupStatus: SignupStatus,
    val nickname: String,
    val imageUrl: String?
)

data class NameAvailability(
    val normalizedValue: String,
    val available: Boolean
)
