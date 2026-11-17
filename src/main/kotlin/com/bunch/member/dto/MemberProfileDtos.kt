package com.bunch.member.dto

import com.bunch.bunch.domain.ProfileImageAction
import com.bunch.member.domain.Gender

/**
 * The final state of the profile edit screen. [demographics] is sent only while the screen shows the birth year and
 * the gender, which is when the member agrees to the optional consent (7.2.6): without it the stored values stay, and
 * with it both values become what the object says, so a null value clears it.
 */
data class MemberProfileRequest(
    val nickname: String,
    val imageAction: ProfileImageAction,
    val demographics: ProfileDemographics? = null
)

data class ProfileDemographics(
    val birthYear: Int? = null,
    val gender: Gender? = null
)
