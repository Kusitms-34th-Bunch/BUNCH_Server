package com.bunch.member.dto

import com.bunch.member.domain.TermType
import com.fasterxml.jackson.annotation.JsonInclude
import java.time.LocalDate

data class TermsDocumentResponse(
    val type: TermType,
    val version: String,
    val effectiveDate: LocalDate,
    val clauses: List<TermsClause>
)

data class TermsClause(
    val title: String,
    val blocks: List<TermsBlock>
)

// PARAGRAPH carries text, LIST carries numbered items. The unused field is omitted.
@JsonInclude(JsonInclude.Include.NON_NULL)
data class TermsBlock(
    val type: TermsBlockType,
    val text: String? = null,
    val items: List<String>? = null
)

enum class TermsBlockType {
    PARAGRAPH,
    LIST
}
