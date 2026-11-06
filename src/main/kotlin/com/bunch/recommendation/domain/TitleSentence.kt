package com.bunch.recommendation.domain

/**
 * One sentence of the title list. [id] never changes and is never used again, so a sentence can be corrected and keeps
 * its place in the history of recently used sentences. A sentence that should go is switched off with [active].
 * [detail] narrows the [situation] (for example `saturday_evening`) and is empty when the situation has no detail.
 * [text] may have `{요일}`, `{A}`, `{B}` and `{N}`, which are filled when the title is made.
 *
 * [DB 연동 지점] 문장을 DB로 옮기면 이 필드가 그대로 테이블 컬럼이 된다: id(기본키), situation, detail, text, active.
 */
data class TitleSentence(
    val id: String,
    val situation: TitleSituation,
    val detail: String,
    val text: String,
    val active: Boolean = true
)
