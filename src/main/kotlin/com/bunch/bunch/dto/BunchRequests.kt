package com.bunch.bunch.dto

import com.bunch.bunch.domain.CharacterType
import com.bunch.bunch.domain.Visibility

data class CreateBunchRequest(
    val name: String,
    val introduction: String,
    val visibility: Visibility,
    val characterType: CharacterType
)

data class InvitationRequest(val invitation: String)
