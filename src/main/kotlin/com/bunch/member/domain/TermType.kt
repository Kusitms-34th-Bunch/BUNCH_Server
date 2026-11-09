package com.bunch.member.domain

enum class TermType(val required: Boolean, val hasDocument: Boolean = false) {
    SERVICE(true, true),
    PRIVACY(true, true),
    AGE_OVER_14(true),
    DEMOGRAPHICS(false)
}
