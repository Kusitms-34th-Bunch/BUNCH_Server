package com.bunch.member.domain

import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

enum class TermType(val required: Boolean) {
    SERVICE(true), PRIVACY(true), AGE_OVER_14(true), DEMOGRAPHICS(false)
}

@Entity
@Table(name = "member_consents")
class MemberConsent(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
    @Column(nullable = false) val memberId: Long,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) val type: TermType,
    @Column(nullable = false, length = 100) val version: String,
    @Column(nullable = false) val agreedAt: Instant = Instant.now()
)

interface MemberConsentRepository : JpaRepository<MemberConsent, Long> {
    fun findAllByMemberId(memberId: Long): List<MemberConsent>
}
