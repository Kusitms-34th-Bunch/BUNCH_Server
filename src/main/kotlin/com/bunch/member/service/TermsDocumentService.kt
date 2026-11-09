package com.bunch.member.service

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.member.domain.MemberRepository
import com.bunch.member.domain.TermType
import com.bunch.member.domain.TermsDocumentRepository
import com.bunch.member.dto.TermsBlock
import com.bunch.member.dto.TermsBlockType
import com.bunch.member.dto.TermsClause
import com.bunch.member.dto.TermsDocumentResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class TermsDocumentService(
    private val members: MemberRepository,
    private val terms: TermsService,
    private val documents: TermsDocumentRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // The current version is the one configured for consent, so the text and the saved consent always match.
    fun current(memberId: Long, type: TermType): TermsDocumentResponse {
        members.findById(memberId)
            .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }
            .requireActive()
        if (type !in DOCUMENT_TYPES) throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        val version = terms.version(type)
        val document = documents.findByTypeAndVersion(type, version) ?: throw unavailable()
        val complete = document.clauses.isNotEmpty() &&
            document.clauses.all { it.isComplete() }
        if (!complete) {
            logger.warn("Terms document is incomplete: type={} version={}", type, version)
            throw unavailable()
        }
        return TermsDocumentResponse(type, document.version, document.effectiveDate, document.clauses)
    }

    private fun unavailable() = BusinessException(ErrorCode.TERMS_UNAVAILABLE)

    private fun TermsClause.isComplete() = title.isNotBlank() &&
        blocks.isNotEmpty() &&
        blocks.all { it.isComplete() }

    private fun TermsBlock.isComplete() = when (type) {
        TermsBlockType.PARAGRAPH -> !text.isNullOrBlank() && items == null
        TermsBlockType.LIST -> text == null &&
            !items.isNullOrEmpty() &&
            items.all { it.isNotBlank() }
    }

    private companion object {
        // The age check has no text (1.3.1) and the analysis consent has no specified text.
        val DOCUMENT_TYPES = setOf(TermType.SERVICE, TermType.PRIVACY)
    }
}
