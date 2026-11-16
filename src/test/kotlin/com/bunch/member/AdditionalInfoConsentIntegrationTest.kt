package com.bunch.member

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.member.domain.AccountStatus
import com.bunch.member.domain.Gender
import com.bunch.member.domain.Member
import com.bunch.member.domain.MemberConsent
import com.bunch.member.domain.MemberConsentRepository
import com.bunch.member.domain.MemberRepository
import com.bunch.member.domain.SignupStatus
import com.bunch.member.domain.TermType
import com.bunch.member.dto.AdditionalInfoConsentRequest
import com.bunch.member.service.AdditionalInfoService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The optional consent to collect birth year and gender (my page specification 7.4.4, 7.4.5): its state is the
 * latest row of the history, a withdrawal deletes the values at once, and a repeated request changes nothing.
 */
@SpringBootTest
@ActiveProfiles("test")
class AdditionalInfoConsentIntegrationTest @Autowired constructor(
    private val additionalInfo: AdditionalInfoService,
    private val members: MemberRepository,
    private val consents: MemberConsentRepository
) {
    private val memberIds = mutableListOf<Long>()

    @AfterEach
    fun cleanup() {
        memberIds.forEach { members.deleteById(it) }
    }

    @Test
    fun `a member who never agreed is not consenting and has no values`() {
        val member = member()

        val state = additionalInfo.get(member.id)

        assertThat(state.consented).isFalse()
        assertThat(state.birthYear).isNull()
        assertThat(state.gender).isNull()
    }

    @Test
    fun `agreeing at signup shows the consent and the values the member gave`() {
        val member = member(birthYear = 2003, gender = Gender.FEMALE)
        consents.save(MemberConsent(memberId = member.id, type = TermType.DEMOGRAPHICS, version = "v1", agreed = true))

        val state = additionalInfo.get(member.id)

        assertThat(state.consented).isTrue()
        assertThat(state.birthYear).isEqualTo(2003)
        assertThat(state.gender).isEqualTo(Gender.FEMALE)
    }

    @Test
    fun `a member who did not agree at signup can agree here and the agreement is kept with its version`() {
        val member = member()

        val state = additionalInfo.change(member.id, agree())

        assertThat(state.consented).isTrue()
        assertThat(history(member.id)).containsExactly(true to "v1")
    }

    @Test
    fun `agreeing twice keeps one agreement`() {
        val member = member()

        additionalInfo.change(member.id, agree())
        val again = additionalInfo.change(member.id, agree())

        assertThat(again.consented).isTrue()
        assertThat(history(member.id)).hasSize(1)
    }

    @Test
    fun `withdrawing deletes the birth year and the gender at once and keeps the history`() {
        val member = member(birthYear = 1999, gender = Gender.MALE)
        additionalInfo.change(member.id, agree())

        val state = additionalInfo.change(member.id, withdraw())

        assertThat(state.consented).isFalse()
        assertThat(state.birthYear).isNull()
        assertThat(state.gender).isNull()
        val saved = members.findById(member.id).get()
        assertThat(saved.birthYear).isNull()
        assertThat(saved.gender).isNull()
        assertThat(history(member.id)).containsExactly(true to "v1", false to "v1")
    }

    @Test
    fun `withdrawing twice records one withdrawal`() {
        val member = member(birthYear = 1999, gender = Gender.MALE)
        additionalInfo.change(member.id, agree())

        additionalInfo.change(member.id, withdraw())
        val again = additionalInfo.change(member.id, withdraw())

        assertThat(again.consented).isFalse()
        assertThat(history(member.id)).hasSize(2)
    }

    @Test
    fun `agreeing again after a withdrawal does not bring the deleted values back`() {
        val member = member(birthYear = 1999, gender = Gender.MALE)
        additionalInfo.change(member.id, agree())
        additionalInfo.change(member.id, withdraw())

        val state = additionalInfo.change(member.id, agree())

        assertThat(state.consented).isTrue()
        assertThat(state.birthYear).isNull()
        assertThat(state.gender).isNull()
        assertThat(history(member.id)).containsExactly(true to "v1", false to "v1", true to "v1")
    }

    @Test
    fun `a member who never agreed can withdraw without anything being recorded`() {
        val member = member()

        val state = additionalInfo.change(member.id, withdraw())

        assertThat(state.consented).isFalse()
        assertThat(history(member.id)).isEmpty()
    }

    @Test
    fun `agreeing needs the version of the text and refuses another one`() {
        val member = member()

        assertThat(error { additionalInfo.change(member.id, AdditionalInfoConsentRequest(true, "v0")) })
            .isEqualTo(ErrorCode.TERMS_VERSION_MISMATCH)
        assertThat(error { additionalInfo.change(member.id, AdditionalInfoConsentRequest(true, null)) })
            .isEqualTo(ErrorCode.INVALID_INPUT)
        assertThat(error { additionalInfo.change(member.id, AdditionalInfoConsentRequest(true, " ")) })
            .isEqualTo(ErrorCode.INVALID_INPUT)
        assertThat(error { additionalInfo.change(member.id, AdditionalInfoConsentRequest(null, "v1")) })
            .isEqualTo(ErrorCode.INVALID_INPUT)
        assertThat(history(member.id)).isEmpty()
    }

    @Test
    fun `a withdrawal needs no version and keeps the version that was agreed to`() {
        val member = member()
        consents.save(MemberConsent(memberId = member.id, type = TermType.DEMOGRAPHICS, version = "v0", agreed = true))

        additionalInfo.change(member.id, AdditionalInfoConsentRequest(false, "anything"))

        assertThat(history(member.id)).containsExactly(true to "v0", false to "v0")
    }

    @Test
    fun `a member who has not finished signing up cannot use it`() {
        val member = member(signup = SignupStatus.IN_PROGRESS)

        assertThat(error { additionalInfo.get(member.id) }).isEqualTo(ErrorCode.SIGNUP_INCOMPLETE)
        assertThat(error { additionalInfo.change(member.id, agree()) }).isEqualTo(ErrorCode.SIGNUP_INCOMPLETE)
        assertThat(history(member.id)).isEmpty()
    }

    @Test
    fun `a withdrawn account and an unknown member are refused`() {
        val gone = member(account = AccountStatus.WITHDRAWN)

        assertThat(error { additionalInfo.get(gone.id) }).isEqualTo(ErrorCode.UNAUTHORIZED)
        assertThat(error { additionalInfo.change(gone.id, agree()) }).isEqualTo(ErrorCode.UNAUTHORIZED)
        assertThat(error { additionalInfo.get(Long.MAX_VALUE) }).isEqualTo(ErrorCode.USER_NOT_FOUND)
        assertThat(error { additionalInfo.change(Long.MAX_VALUE, agree()) }).isEqualTo(ErrorCode.USER_NOT_FOUND)
    }

    @Test
    fun `requests of one member sent at once agree once`() {
        val member = member()

        runTogether(8) { additionalInfo.change(member.id, agree()) }

        assertThat(history(member.id)).containsExactly(true to "v1")
    }

    @Test
    fun `an agreement and a withdrawal sent at once end in the state of the last one that ran`() {
        repeat(5) {
            val member = member(birthYear = 1980, gender = Gender.MALE)
            additionalInfo.change(member.id, agree())

            runTogether(2) { index ->
                val request = if (index == 0) withdraw() else agree()
                additionalInfo.change(member.id, request)
            }

            val rows = history(member.id)
            val saved = members.findById(member.id).get()
            // The last row says whether the member consents, and a member who does not has no values.
            if (!rows.last().first) {
                assertThat(saved.birthYear).isNull()
                assertThat(saved.gender).isNull()
            }
            assertThat(additionalInfo.get(member.id).consented).isEqualTo(rows.last().first)
            // The history never holds two rows in a row that say the same.
            rows.zipWithNext().forEach { (before, after) -> assertThat(after.first).isNotEqualTo(before.first) }
        }
    }

    private fun agree() = AdditionalInfoConsentRequest(true, "v1")

    private fun withdraw() = AdditionalInfoConsentRequest(false)

    private fun history(memberId: Long): List<Pair<Boolean, String>> = consents.findAllByMemberId(memberId)
        .filter { it.type == TermType.DEMOGRAPHICS }
        .sortedBy { it.id }
        .map { it.agreed to it.version }

    private fun error(action: () -> Unit): ErrorCode = assertThrows<BusinessException> { action() }.errorCode

    // Starts every call at the same moment and waits for all of them. A call that fails fails the test.
    private fun runTogether(count: Int, action: (Int) -> Unit) {
        val pool = Executors.newFixedThreadPool(count)
        try {
            val ready = CountDownLatch(count)
            val go = CountDownLatch(1)
            val results = (0 until count).map { index ->
                pool.submit(
                    Callable {
                        ready.countDown()
                        go.await()
                        action(index)
                    }
                )
            }
            ready.await(10, TimeUnit.SECONDS)
            go.countDown()
            results.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun member(
        birthYear: Int? = null,
        gender: Gender? = null,
        signup: SignupStatus = SignupStatus.COMPLETED,
        account: AccountStatus = AccountStatus.ACTIVE
    ): Member = members.saveAndFlush(
        Member(
            signupStatus = signup,
            accountStatus = account,
            nickname = "동의${UUID.randomUUID().toString().take(8)}",
            birthYear = birthYear,
            gender = gender
        )
    ).also { memberIds += it.id }
}
