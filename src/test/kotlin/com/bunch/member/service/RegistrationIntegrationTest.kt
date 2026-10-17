package com.bunch.member.service

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.image.ProfileImageStorage
import com.bunch.global.image.StoredImage
import com.bunch.member.domain.*
import com.bunch.member.dto.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.ActiveProfiles
import org.springframework.mock.web.MockMultipartFile
import java.time.Year
import java.time.ZoneId
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@ActiveProfiles("test")
class RegistrationIntegrationTest @Autowired constructor(
    private val registrations: RegistrationService,
    private val terms: TermsService,
    private val members: MemberRepository,
    private val consents: MemberConsentRepository,
    private val jdbc: org.springframework.jdbc.core.JdbcTemplate
) {
    @MockitoBean lateinit var images: ProfileImageStorage
    private val created = mutableListOf<Long>()

    @AfterEach
    fun cleanup() { created.forEach { members.deleteById(it) } }

    private fun member(agreed: Boolean = true): Member = members.saveAndFlush(Member()).also {
        created += it.id
        if (agreed) agree(it.id)
    }

    private fun agree(id: Long) = terms.agree(id, TermsRequest(TermType.entries.filter { it.required }
        .map { TermAgreement(it, "v1", true) }))

    @Test
    fun `required consents are atomic versioned and idempotent`() {
        val member = member(false)
        assertThat(terms.accepted(member.id)).isFalse()
        assertThrows<BusinessException> { terms.agree(member.id, TermsRequest(listOf(TermAgreement(TermType.SERVICE, "v1", true)))) }
        assertThat(consents.findAllByMemberId(member.id)).isEmpty()
        val bad = TermsRequest(TermType.entries.filter { it.required }.map { TermAgreement(it, "old", true) })
        assertThat(assertThrows<BusinessException> { terms.agree(member.id, bad) }.errorCode).isEqualTo(ErrorCode.TERMS_VERSION_MISMATCH)
        agree(member.id)
        val before = consents.findAllByMemberId(member.id).map { it.id to it.agreedAt }
        agree(member.id)
        assertThat(consents.findAllByMemberId(member.id).map { it.id to it.agreedAt }).containsExactlyElementsOf(before)
        assertThat(terms.accepted(member.id)).isTrue()
    }

    @Test
    fun `registration requires consent and persists completion for reentry`() {
        val member = member(false)
        assertThat(assertThrows<BusinessException> { registrations.complete(member.id, RegistrationRequest("번치"), null) }.errorCode)
            .isEqualTo(ErrorCode.SIGNUP_INCOMPLETE)
        assertThat(members.findById(member.id).get().signupStatus).isEqualTo(SignupStatus.IN_PROGRESS)
        agree(member.id)
        val result = registrations.complete(member.id, RegistrationRequest("  번치  "), null)
        assertThat(result.nickname).isEqualTo("번치")
        assertThat(result.imageUrl).isNull()
        val saved = members.findById(member.id).get()
        assertThat(saved.signupStatus).isEqualTo(SignupStatus.COMPLETED)
        assertThat(saved.completedAt).isNotNull()
        assertThat(registrations.complete(member.id, RegistrationRequest("번치"), null)).isEqualTo(result)
        assertThat(assertThrows<BusinessException> { registrations.complete(member.id, RegistrationRequest("다른이름"), null) }.errorCode)
            .isEqualTo(ErrorCode.REGISTRATION_CONFLICT)
    }

    @Test
    fun `demographics require optional consent and remain optional`() {
        val member = member()
        assertThat(assertThrows<BusinessException> { registrations.complete(member.id, RegistrationRequest("선택", 1990, Gender.FEMALE), null) }.errorCode)
            .isEqualTo(ErrorCode.INVALID_INPUT)
        assertThat(consents.findAllByMemberId(member.id)).hasSize(3)
        registrations.complete(member.id, RegistrationRequest("선택", 1990, Gender.FEMALE, "v1"), null)
        assertThat(members.findById(member.id).get().birthYear).isEqualTo(1990)
        assertThat(members.findById(member.id).get().gender).isEqualTo(Gender.FEMALE)
        assertThat(consents.findAllByMemberId(member.id).map { it.type }).contains(TermType.DEMOGRAPHICS)
    }

    @Test
    fun `birth year bounds are enforced independently of age attestation`() {
        val member = member()
        val latest = Year.now(ZoneId.of("Asia/Seoul")).value - 14
        listOf(1949, latest + 1).forEach { year ->
            assertThat(assertThrows<BusinessException> { registrations.complete(member.id, RegistrationRequest("연도", year, null, "v1"), null) }.errorCode)
                .isEqualTo(ErrorCode.INVALID_INPUT)
        }
        registrations.complete(member.id, RegistrationRequest("연도", 1950, null, "v1"), null)
        assertThat(members.findById(member.id).get().birthYear).isEqualTo(1950)
    }

    @Test
    fun `photo failure does not roll back signup and response loss does not upload twice`() {
        val member = member()
        val photo = MockMultipartFile("photo", "profile.jpg", "image/jpeg", byteArrayOf(1, 2, 3))
        `when`(images.upload(photo)).thenThrow(BusinessException(ErrorCode.PROFILE_IMAGE_FAILED))
        val result = registrations.complete(member.id, RegistrationRequest("사진실패"), photo)
        assertThat(result.signupStatus).isEqualTo(SignupStatus.COMPLETED)
        assertThat(result.imageUrl).isNull()
        assertThat(registrations.complete(member.id, RegistrationRequest("사진실패"), photo)).isEqualTo(result)
        verify(images, times(1)).upload(photo)
    }

    @Test
    fun `photo is stored only when required profile is valid`() {
        val member = member()
        val photo = MockMultipartFile("photo", "profile.jpg", "image/jpeg", byteArrayOf(4, 5))
        assertThrows<BusinessException> { registrations.complete(member.id, RegistrationRequest("bad!"), photo) }
        verifyNoInteractions(images)
        `when`(images.upload(photo)).thenReturn(StoredImage("profile-id", "https://res.cloudinary.com/test/profile.jpg"))
        val result = registrations.complete(member.id, RegistrationRequest("사진성공"), photo)
        assertThat(result.imageUrl).endsWith("profile.jpg")
        assertThat(members.findById(member.id).get().imagePublicId).isEqualTo("profile-id")
    }

    @Test
    fun `failed database profile save removes new image and keeps signup incomplete`() {
        val member = member()
        val photo = MockMultipartFile("photo", "profile.jpg", "image/jpeg", byteArrayOf(6, 7))
        `when`(images.upload(photo)).thenReturn(StoredImage("rollback-profile", "https://res.cloudinary.com/test/rollback.jpg"))
        jdbc.execute("CREATE FUNCTION registration_test_reject() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''profile write failed''; END'")
        try {
            jdbc.execute("CREATE TRIGGER registration_test_reject BEFORE UPDATE ON members FOR EACH ROW WHEN (NEW.id = ${member.id}) EXECUTE FUNCTION registration_test_reject()")
            assertThrows<RuntimeException> { registrations.complete(member.id, RegistrationRequest("저장실패"), photo) }
            verify(images).delete("rollback-profile")
            val saved = members.findById(member.id).get()
            assertThat(saved.signupStatus).isEqualTo(SignupStatus.IN_PROGRESS)
            assertThat(saved.nickname).isNull()
            assertThat(saved.imagePublicId).isNull()
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS registration_test_reject ON members")
            jdbc.execute("DROP FUNCTION IF EXISTS registration_test_reject()")
        }
    }

    @Test
    fun `missing terms publication configuration does not invent an active version`() {
        val unconfigured = TermsService(members, consents, "", "", "", "")
        assertThat(assertThrows<BusinessException> { unconfigured.current() }.errorCode).isEqualTo(ErrorCode.TERMS_UNAVAILABLE)
    }

    @Test
    fun `nickname identity preserves case and internal spaces but normalizes NFC`() {
        registrations.complete(member().id, RegistrationRequest("Bunch"), null)
        val another = member()
        assertThat(registrations.nicknameAvailable(another.id, " Bunch ").available).isFalse()
        assertThat(registrations.nicknameAvailable(another.id, "bunch").available).isTrue()
        assertThat(registrations.nicknameAvailable(another.id, "Bun ch").available).isTrue()
        registrations.complete(another.id, RegistrationRequest("번치"), null)
        assertThat(registrations.nicknameAvailable(another.id, "번치").available).isFalse()
    }

    @Test
    fun `repeated uncombined Hangul cannot bypass name length in availability or registration`() {
        val member = member()
        val oversized = "\u1161".repeat(121)
        assertThat(assertThrows<BusinessException> { registrations.nicknameAvailable(member.id, oversized) }.errorCode)
            .isEqualTo(ErrorCode.INVALID_INPUT)
        assertThat(assertThrows<BusinessException> { registrations.complete(member.id, RegistrationRequest(oversized), null) }.errorCode)
            .isEqualTo(ErrorCode.INVALID_INPUT)
        assertThat(members.findById(member.id).get().signupStatus).isEqualTo(SignupStatus.IN_PROGRESS)
    }

    @Test
    fun `two registrations cannot claim the same nickname`() {
        val first = member()
        val second = member()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = executor.invokeAll(listOf(first.id, second.id).map { id -> Callable {
                try { registrations.complete(id, RegistrationRequest("동시가입"), null); null }
                catch (e: BusinessException) { e.errorCode }
            } }).map { it.get(10, TimeUnit.SECONDS) }
            assertThat(results.count { it == null }).isEqualTo(1)
            assertThat(results.filterNotNull()).containsExactly(ErrorCode.NICKNAME_TAKEN)
            assertThat(listOf(first, second).count { members.findById(it.id).get().signupStatus == SignupStatus.COMPLETED }).isEqualTo(1)
        } finally { executor.shutdownNow() }
    }

    @Test
    fun `suspended member cannot continue registration`() {
        val member = member()
        member.accountStatus = AccountStatus.SUSPENDED
        members.saveAndFlush(member)
        assertThat(assertThrows<BusinessException> { registrations.complete(member.id, RegistrationRequest("정지"), null) }.errorCode)
            .isEqualTo(ErrorCode.UNAUTHORIZED)
    }
}
