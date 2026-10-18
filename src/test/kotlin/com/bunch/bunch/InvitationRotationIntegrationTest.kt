package com.bunch.bunch

import com.bunch.bunch.service.InvitationInput
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doAnswer
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@ActiveProfiles("test")
class InvitationRotationIntegrationTest : BunchIntegrationSupport() {
    @MockitoSpyBean private lateinit var input: InvitationInput

    @Test
    fun `join rejects invitation revoked after resolution before acquiring bunch lock`() {
        val owner = member()
        val visitor = member()
        val bunch = create(owner)
        val invitation = issue(owner, bunch.id)
        val resolved = CountDownLatch(1)
        val revoked = CountDownLatch(1)
        doAnswer { invocation ->
            val result = invocation.callRealMethod()
            resolved.countDown()
            check(revoked.await(10, TimeUnit.SECONDS))
            result
        }.`when`(input).resolve(invitation.code)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val joining = executor.submit<Result<Any>> {
                runCatching { invitations.join(visitor, java.util.UUID.randomUUID(), invitation.code) }
            }
            check(resolved.await(10, TimeUnit.SECONDS))
            jdbc.update("UPDATE bunch_invitations SET revoked_at = CURRENT_TIMESTAMP WHERE bunch_id = ?", bunch.id)
            revoked.countDown()
            val result = joining.get(15, TimeUnit.SECONDS)
            assertThat(result.isFailure).isTrue()
            assertThat((result.exceptionOrNull() as BusinessException).errorCode).isEqualTo(ErrorCode.INVALID_INVITATION)
            assertThat(count("bunch_memberships")).isEqualTo(1)
            assertThat(count("bunch_join_notifications")).isZero()
        } finally {
            revoked.countDown()
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}
