package com.bunch.member.domain

import jakarta.persistence.*
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import java.time.Instant

@Entity
@Table(name = "members")
class Member(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column
    val email: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    val role: MemberRole = MemberRole.ROLE_USER,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var signupStatus: SignupStatus = SignupStatus.IN_PROGRESS,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var accountStatus: AccountStatus = AccountStatus.ACTIVE,

    @Column(length = 120, unique = true)
    var nickname: String? = null,

    var birthYear: Int? = null,

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    var gender: Gender? = null,

    var imagePublicId: String? = null,

    var imageUrl: String? = null,

    var completedAt: Instant? = null,

    @Column(length = 64)
    var registrationFingerprint: String? = null
) {
    fun requireActive(): Member {
        if (accountStatus != AccountStatus.ACTIVE) throw BusinessException(ErrorCode.UNAUTHORIZED)
        return this
    }

    fun requireCompleted(): Member {
        requireActive()
        if (signupStatus != SignupStatus.COMPLETED) throw BusinessException(ErrorCode.SIGNUP_INCOMPLETE)
        return this
    }
}
