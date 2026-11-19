package com.bunch.member.service

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.oidc.AppleTokenRevoker
import com.bunch.global.oidc.AuthProvider
import com.bunch.member.domain.ExternalIdentityRepository
import org.springframework.stereotype.Service

/**
 * Orders a withdrawal around the call to Apple. There is deliberately no transaction here: the call to
 * Apple must not hold the locks of the withdrawal, which runs in its own transaction afterwards.
 */
@Service
class MemberWithdrawalCoordinator(
    private val withdrawals: MemberWithdrawalService,
    private val identities: ExternalIdentityRepository,
    private val apple: AppleTokenRevoker
) {
    fun withdraw(memberId: Long, appleAuthorizationCode: String?) {
        // A member who still has to hand over a bunch must not lose the Apple connection first (7.6.4 logic 1).
        withdrawals.requireNoOwnerBunches(memberId)
        identities.findByMemberIdAndProvider(memberId, AuthProvider.APPLE)?.let { identity ->
            val code = appleAuthorizationCode?.takeIf { it.isNotBlank() }
                ?: throw BusinessException(ErrorCode.APPLE_AUTHORIZATION_CODE_REQUIRED)
            // A failure here stops the withdrawal and leaves the account as it was (7.6.4 failure).
            apple.revoke(code, identity.providerSubject)
        }
        withdrawals.withdraw(memberId)
    }
}
