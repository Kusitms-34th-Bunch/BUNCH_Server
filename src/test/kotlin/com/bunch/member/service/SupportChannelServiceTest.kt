package com.bunch.member.service

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.member.domain.AccountStatus
import com.bunch.member.domain.Member
import com.bunch.member.domain.MemberRepository
import com.bunch.member.domain.SignupStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.Optional

class SupportChannelServiceTest {
    private val members = mock(MemberRepository::class.java)

    private fun service(url: String): SupportChannelService {
        `when`(members.findById(MEMBER_ID)).thenReturn(
            Optional.of(Member(signupStatus = SignupStatus.COMPLETED))
        )
        return SupportChannelService(members, url)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://open.kakao.com/o/gAbCdEf",
            "  https://open.kakao.com/o/gAbCdEf  ",
            "HTTPS://open.kakao.com/o/gAbCdEf",
            "https://open.kakao.com:8443/o/gAbCdEf?from=my",
            "https://example.com"
        ]
    )
    fun `an https link is returned without surrounding spaces`(value: String) {
        assertThat(service(value).current(MEMBER_ID).openChatUrl).isEqualTo(value.trim())
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "   ",
            "http://open.kakao.com/o/gAbCdEf",
            "ftp://open.kakao.com/o/gAbCdEf",
            "javascript:alert(1)",
            "open.kakao.com/o/gAbCdEf",
            "//open.kakao.com/o/gAbCdEf",
            "https:///o/gAbCdEf",
            "https://user:secret@open.kakao.com/o/gAbCdEf",
            "https://open.kakao.com/o/ga bcdef",
            "https://open.kakao.com/o/gAbCdEf\nhttps://evil.example",
            "https://open kakao.com/o/gAbCdEf",
            "https://[invalid/o/x"
        ]
    )
    fun `a missing or unsafe setting makes the channel unavailable`(value: String) {
        val error = assertThrows<BusinessException> { service(value).current(MEMBER_ID) }

        assertThat(error.errorCode).isEqualTo(ErrorCode.SUPPORT_CHANNEL_UNAVAILABLE)
    }

    @Test
    fun `a link longer than the limit is unavailable`() {
        val tooLong = "https://open.kakao.com/o/" + "a".repeat(2048)

        val error = assertThrows<BusinessException> { service(tooLong).current(MEMBER_ID) }

        assertThat(error.errorCode).isEqualTo(ErrorCode.SUPPORT_CHANNEL_UNAVAILABLE)
    }

    @Test
    fun `an unfinished signup is refused before the link is read`() {
        `when`(members.findById(MEMBER_ID)).thenReturn(
            Optional.of(Member(signupStatus = SignupStatus.IN_PROGRESS))
        )

        val error = assertThrows<BusinessException> {
            SupportChannelService(members, "https://open.kakao.com/o/gAbCdEf").current(MEMBER_ID)
        }

        assertThat(error.errorCode).isEqualTo(ErrorCode.SIGNUP_INCOMPLETE)
    }

    @Test
    fun `suspended and withdrawn accounts are refused`() {
        listOf(AccountStatus.SUSPENDED, AccountStatus.WITHDRAWN).forEach { status ->
            `when`(members.findById(MEMBER_ID)).thenReturn(
                Optional.of(Member(signupStatus = SignupStatus.COMPLETED, accountStatus = status))
            )

            val error = assertThrows<BusinessException> {
                SupportChannelService(members, "https://open.kakao.com/o/gAbCdEf").current(MEMBER_ID)
            }

            assertThat(error.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
        }
    }

    @Test
    fun `an unknown member is not found`() {
        `when`(members.findById(MEMBER_ID)).thenReturn(Optional.empty())

        val error = assertThrows<BusinessException> {
            SupportChannelService(members, "https://open.kakao.com/o/gAbCdEf").current(MEMBER_ID)
        }

        assertThat(error.errorCode).isEqualTo(ErrorCode.USER_NOT_FOUND)
    }

    private companion object {
        const val MEMBER_ID = 1L
    }
}
