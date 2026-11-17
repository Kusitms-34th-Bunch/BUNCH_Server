package com.bunch.member.dto

import com.bunch.member.domain.Gender
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

// The state of the optional demographics consent and the values it allows to keep (7.4.4, 7.2.6).
// Without the consent both values are null.
data class AdditionalInfoResponse(
    val consented: Boolean,
    val birthYear: Int?,
    val gender: Gender?
)

// Agreeing names the version of the text the member saw. Withdrawing needs no version (7.4.5).
data class AdditionalInfoConsentRequest(
    @field:NotNull
    val agreed: Boolean?,

    @field:Size(max = 100)
    val version: String? = null
)
