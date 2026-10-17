package com.bunch.member.domain

enum class TermType(val required: Boolean) {
    SERVICE(true), PRIVACY(true), AGE_OVER_14(true), DEMOGRAPHICS(false)
}
