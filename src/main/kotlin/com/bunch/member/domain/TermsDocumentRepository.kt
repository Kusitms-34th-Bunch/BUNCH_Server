package com.bunch.member.domain

import org.springframework.data.jpa.repository.JpaRepository

interface TermsDocumentRepository : JpaRepository<TermsDocument, Long> {
    fun findByTypeAndVersion(type: TermType, version: String): TermsDocument?
}
