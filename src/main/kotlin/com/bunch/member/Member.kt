package com.bunch.member
import jakarta.persistence.*
@Entity
@Table(name = "members")
class Member(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
    @Column val email: String? = null,
    @Column(nullable = false, length = 32) val role: String = "ROLE_USER"
)
enum class AuthProvider { APPLE, GOOGLE, KAKAO }
@Entity
@Table(name = "external_identity", uniqueConstraints = [UniqueConstraint(name = "uk_identity_provider_subject", columnNames = ["provider", "provider_subject"])])
class ExternalIdentity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "member_id", nullable = false) val member: Member,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) val provider: AuthProvider,
    @Column(name = "provider_subject", nullable = false, length = 255) val providerSubject: String,
    @Column(name = "email_at_link_time") val emailAtLinkTime: String? = null
)
