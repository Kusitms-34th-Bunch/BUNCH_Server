package com.bunch.member

import com.bunch.bunch.domain.CharacterType
import com.bunch.bunch.domain.ProfileImageAction
import com.bunch.bunch.domain.Visibility
import com.bunch.bunch.dto.CreateBunchRequest
import com.bunch.bunch.service.BunchPublicService
import com.bunch.bunch.service.BunchService
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.image.ProfileImageStorage
import com.bunch.global.image.StoredImage
import com.bunch.global.profanity.ProfanityDictionary
import com.bunch.global.profanity.ProfanityFilter
import com.bunch.image.domain.ImageAsset
import com.bunch.image.domain.ImageAssetRepository
import com.bunch.member.domain.AccountStatus
import com.bunch.member.domain.Gender
import com.bunch.member.domain.Member
import com.bunch.member.domain.MemberConsentRepository
import com.bunch.member.domain.MemberRepository
import com.bunch.member.domain.SignupStatus
import com.bunch.member.dto.AdditionalInfoConsentRequest
import com.bunch.member.dto.MemberProfileRequest
import com.bunch.member.dto.ProfileDemographics
import com.bunch.member.service.AdditionalInfoService
import com.bunch.member.service.MemberProfileService
import com.bunch.notification.domain.Notification
import com.bunch.notification.domain.NotificationRepository
import com.bunch.notification.domain.NotificationTargetKind
import com.bunch.notification.domain.NotificationType
import com.bunch.notification.dto.NotificationTarget
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.`when`
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.web.multipart.MultipartFile
import java.text.Normalizer
import java.time.Instant
import java.time.Year
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@ActiveProfiles("test")
class MemberProfileEditIntegrationTest @Autowired constructor(
    private val service: MemberProfileService,
    private val additionalInfos: AdditionalInfoService,
    private val consents: MemberConsentRepository,
    private val members: MemberRepository,
    private val assets: ImageAssetRepository,
    private val bunches: BunchService,
    private val publicBunches: BunchPublicService,
    private val notifications: NotificationRepository,
    private val jdbc: JdbcTemplate
) {
    @MockitoBean
    lateinit var images: ProfileImageStorage

    @MockitoSpyBean
    lateinit var profanity: ProfanityFilter

    private val memberIds = mutableListOf<Long>()
    private val publicIds = mutableListOf<String>()

    @AfterEach
    fun cleanup() {
        memberIds.forEach { jdbc.update("DELETE FROM notifications WHERE recipient_member_id = ?", it) }
        memberIds.forEach { jdbc.update("DELETE FROM bunches WHERE created_by = ?", it) }
        memberIds.forEach { members.deleteById(it) }
        publicIds.forEach { jdbc.update("DELETE FROM image_assets WHERE object_key = ?", it) }
    }

    @Test
    fun `the nickname and the image are saved from the final state in one request`() {
        val member = member(image = asset("before"))
        val nickname = unique("새")

        edit(member, nickname = "  $nickname  ", action = ProfileImageAction.RESET)

        val saved = saved(member)
        assertThat(saved.nickname).isEqualTo(nickname)
        assertThat(saved.profileImageAsset).isNull()
    }

    @Test
    fun `a member without any bunch can edit the profile`() {
        val member = member()
        val nickname = unique("무소속")

        edit(member, nickname = nickname)

        assertThat(saved(member).nickname).isEqualTo(nickname)
    }

    @Test
    fun `nickname follows the sign up rules`() {
        val member = member()
        val current = member.nickname!!

        listOf("", "   ", "가".repeat(13), "닉네임!${hex(4)}", "이모지😀${hex(4)}").forEach {
            assertThat(assertThrows<BusinessException> { edit(member, nickname = it) }.errorCode)
                .isEqualTo(ErrorCode.INVALID_INPUT)
        }
        assertThat(saved(member).nickname).isEqualTo(current)

        val twelve = "가".repeat(4) + hex(8)
        edit(member, nickname = twelve)
        assertThat(saved(member).nickname).isEqualTo(twelve)
    }

    @Test
    fun `decomposed Hangul is stored in composed form`() {
        val member = member()
        val composed = "한글" + hex(6)

        edit(member, nickname = Normalizer.normalize(composed, Normalizer.Form.NFD))

        assertThat(saved(member).nickname).isEqualTo(composed)
    }

    @Test
    fun `a member may keep the own nickname while the image changes`() {
        val member = member(image = asset("own"))

        edit(member, action = ProfileImageAction.RESET)

        assertThat(saved(member).nickname).isEqualTo(member.nickname)
        assertThat(saved(member).profileImageAsset).isNull()
    }

    @Test
    fun `a nickname of another member is rejected and nothing else changes`() {
        val other = member()
        val old = asset("taken")
        val member = member(image = old)

        val error = assertThrows<BusinessException> {
            edit(member, nickname = other.nickname!!, action = ProfileImageAction.RESET)
        }

        assertThat(error.errorCode).isEqualTo(ErrorCode.NICKNAME_TAKEN)
        val saved = saved(member)
        assertThat(saved.nickname).isEqualTo(member.nickname)
        assertThat(saved.profileImageAsset?.id).isEqualTo(old.id)
    }

    @Test
    fun `a prohibited nickname is rejected before replacement upload`() {
        val member = member(image = asset("prohibited"))
        val old = saved(member).profileImageAsset
        val photo = photo(1, 2, 3)

        val error = assertThrows<BusinessException> {
            edit(member, nickname = "씨발", action = ProfileImageAction.REPLACE, photo = photo)
        }

        assertThat(error.errorCode).isEqualTo(ErrorCode.PROHIBITED_WORD)
        assertThat(error.detail).isEqualTo(mapOf("fields" to listOf("nickname")))
        verifyNoInteractions(images)
        assertThat(saved(member).nickname).isEqualTo(member.nickname)
        assertThat(saved(member).profileImageAsset?.id).isEqualTo(old?.id)
    }

    @Test
    fun `profile final save rechecks profanity and compensates uploaded file`() {
        val dictionaries = java.util.concurrent.atomic.AtomicReference(
            ProfanityDictionary(listOf("씨발", "시발", "ㅅㅂ"))
        )
        org.mockito.Mockito.`when`(profanity.current()).thenAnswer { dictionaries.get() }
        val old = asset("profile-profanity-old")
        val member = consenting(member(image = old, birthYear = 1990, gender = Gender.MALE))
        val photo = photo(4, 5, 6)
        val uploaded = StoredImage(publicId("profile-profanity-new"))
        `when`(images.upload(photo)).thenAnswer {
            dictionaries.set(ProfanityDictionary(listOf("씨발", "시발", "ㅅㅂ", "fuck")))
            uploaded
        }

        val error = assertThrows<BusinessException> {
            edit(
                member,
                nickname = "fuck",
                action = ProfileImageAction.REPLACE,
                photo = photo,
                demographics = ProfileDemographics(2000, Gender.FEMALE)
            )
        }

        assertThat(error.errorCode).isEqualTo(ErrorCode.PROHIBITED_WORD)
        assertThat(saved(member).nickname).isEqualTo(member.nickname)
        assertThat(saved(member).profileImageAsset?.id).isEqualTo(old.id)
        assertThat(saved(member).birthYear).isEqualTo(1990)
        assertThat(saved(member).gender).isEqualTo(Gender.MALE)
        verify(images).delete(uploaded.objectKey)
    }

    @Test
    fun `nickname identity keeps case and inner spaces`() {
        val suffix = hex(4)
        member(nickname = "Ab$suffix")
        member(nickname = "번치 클럽$suffix")
        val upper = member()
        val spaced = member()

        edit(upper, nickname = "ab$suffix")
        edit(spaced, nickname = "번치클럽$suffix")

        assertThat(saved(upper).nickname).isEqualTo("ab$suffix")
        assertThat(saved(spaced).nickname).isEqualTo("번치클럽$suffix")
    }

    @Test
    fun `two members racing for one nickname leave exactly one winner`() {
        val first = member()
        val second = member()
        val shared = unique("경합")
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)

        val results = listOf(first, second)
            .map { member -> pool.submit(Callable { start.await(); runCatching { edit(member, nickname = shared) } }) }
            .also { start.countDown() }
            .map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        val failure = results.single { it.isFailure }.exceptionOrNull() as BusinessException
        assertThat(failure.errorCode).isEqualTo(ErrorCode.NICKNAME_TAKEN)
    }

    @Test
    fun `a request without demographics keeps the birth year and the gender`() {
        val member = consenting(member(birthYear = 1990, gender = Gender.MALE))

        edit(member, nickname = unique("그대로"))

        val saved = saved(member)
        assertThat(saved.birthYear).isEqualTo(1990)
        assertThat(saved.gender).isEqualTo(Gender.MALE)
    }

    @Test
    fun `a consenting member changes the birth year and the gender with the rest of the profile`() {
        val member = consenting(member(birthYear = 1990, gender = Gender.MALE))
        val nickname = unique("함께")

        edit(member, nickname = nickname, demographics = ProfileDemographics(2003, Gender.FEMALE))

        val saved = saved(member)
        assertThat(saved.nickname).isEqualTo(nickname)
        assertThat(saved.birthYear).isEqualTo(2003)
        assertThat(saved.gender).isEqualTo(Gender.FEMALE)
    }

    @Test
    fun `the two values are replaced together and an empty one is cleared`() {
        val member = consenting(member(birthYear = 1990, gender = Gender.MALE))

        edit(member, demographics = ProfileDemographics(null, Gender.FEMALE))
        assertThat(saved(member).birthYear).isNull()
        assertThat(saved(member).gender).isEqualTo(Gender.FEMALE)

        edit(member, demographics = ProfileDemographics(2001, null))
        assertThat(saved(member).birthYear).isEqualTo(2001)
        assertThat(saved(member).gender).isNull()

        edit(member, demographics = ProfileDemographics())
        assertThat(saved(member).birthYear).isNull()
        assertThat(saved(member).gender).isNull()
    }

    @Test
    fun `the same demographics sent twice end in the same state`() {
        val member = consenting(member())

        repeat(2) { edit(member, demographics = ProfileDemographics(1999, Gender.MALE)) }

        assertThat(saved(member).birthYear).isEqualTo(1999)
        assertThat(saved(member).gender).isEqualTo(Gender.MALE)
    }

    @Test
    fun `a member who never agreed is refused and nothing else changes`() {
        val member = member(nickname = unique("거부"))
        val before = saved(member).nickname

        val error = assertThrows<BusinessException> {
            edit(member, nickname = unique("바꿈"), demographics = ProfileDemographics(2000, Gender.MALE))
        }

        assertThat(error.errorCode).isEqualTo(ErrorCode.FORBIDDEN)
        val saved = saved(member)
        assertThat(saved.nickname).isEqualTo(before)
        assertThat(saved.birthYear).isNull()
        assertThat(saved.gender).isNull()
    }

    @Test
    fun `a member who withdrew the consent is refused even with empty values`() {
        val member = consenting(member(birthYear = 1990, gender = Gender.MALE))
        additionalInfos.change(member.id, AdditionalInfoConsentRequest(false))

        val error = assertThrows<BusinessException> { edit(member, demographics = ProfileDemographics()) }

        assertThat(error.errorCode).isEqualTo(ErrorCode.FORBIDDEN)
        assertThat(saved(member).birthYear).isNull()
    }

    @Test
    fun `the birth year is from 1930 to this year minus 14 and a wrong one changes nothing`() {
        val member = consenting(member(nickname = unique("범위"), birthYear = 1990))
        val before = saved(member).nickname
        val latest = Year.now(ZoneId.of("Asia/Seoul")).value - 14

        listOf(1929, latest + 1, 0, -1, 20000).forEach { year ->
            val error = assertThrows<BusinessException> {
                edit(member, nickname = unique("틀림"), demographics = ProfileDemographics(year, Gender.MALE))
            }
            assertThat(error.errorCode).describedAs("year $year").isEqualTo(ErrorCode.INVALID_INPUT)
        }
        assertThat(saved(member).nickname).isEqualTo(before)
        assertThat(saved(member).birthYear).isEqualTo(1990)

        edit(member, demographics = ProfileDemographics(1930, null))
        assertThat(saved(member).birthYear).isEqualTo(1930)
        edit(member, demographics = ProfileDemographics(latest, null))
        assertThat(saved(member).birthYear).isEqualTo(latest)
    }

    @Test
    fun `a withdrawal and an edit at once leave the consent off and no values`() {
        repeat(3) {
            val member = consenting(member(birthYear = 1990, gender = Gender.MALE))
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)

            val results = listOf<() -> Unit>(
                { edit(member, demographics = ProfileDemographics(2002, Gender.FEMALE)) },
                { additionalInfos.change(member.id, AdditionalInfoConsentRequest(false)) }
            ).map { action -> pool.submit(Callable { start.await(); runCatching(action) }) }
                .also { start.countDown() }
                .map { it.get(30, TimeUnit.SECONDS) }
            pool.shutdown()

            // The edit either ran first and was cleared by the withdrawal, or was refused after it.
            results.filter { it.isFailure }.forEach {
                assertThat((it.exceptionOrNull() as BusinessException).errorCode).isEqualTo(ErrorCode.FORBIDDEN)
            }
            assertThat(additionalInfos.consenting(member.id)).isFalse()
            assertThat(saved(member).birthYear).isNull()
            assertThat(saved(member).gender).isNull()
        }
    }

    @Test
    fun `resetting the image removes it and releases the old file after commit`() {
        val old = asset("old")
        val member = member(image = old)

        edit(member, action = ProfileImageAction.RESET)

        assertThat(saved(member).profileImageAsset).isNull()
        assertThat(assets.findById(old.id)).isEmpty
        verify(images).delete(old.objectKey)
    }

    @Test
    fun `keeping the image changes neither the asset nor the file`() {
        val old = asset("keep")
        val member = member(image = old)

        edit(member, nickname = unique("유지"))

        assertThat(saved(member).profileImageAsset?.id).isEqualTo(old.id)
        verify(images, never()).delete(old.objectKey)
    }

    @Test
    fun `replacing the image stores the new one and releases the old file after commit`() {
        val old = asset("before")
        val member = member(image = old)
        val photo = photo(1, 2, 3)
        val uploaded = StoredImage(publicId("after"))
        `when`(images.upload(photo)).thenReturn(uploaded)

        edit(member, action = ProfileImageAction.REPLACE, photo = photo)

        assertThat(saved(member).imageKey).isEqualTo(uploaded.objectKey)
        assertThat(assets.findById(old.id)).isEmpty
        verify(images).delete(old.objectKey)
        verify(images, never()).delete(uploaded.objectKey)
    }

    @Test
    fun `the photo part must match the image action`() {
        val member = member()
        val photo = photo(1, 2, 3)

        listOf(
            ProfileImageAction.KEEP to photo,
            ProfileImageAction.RESET to photo,
            ProfileImageAction.REPLACE to null,
            ProfileImageAction.REPLACE to MockMultipartFile("photo", "empty.jpg", "image/jpeg", ByteArray(0))
        ).forEach { (action, part) ->
            assertThat(assertThrows<BusinessException> { edit(member, action = action, photo = part) }.errorCode)
                .isEqualTo(ErrorCode.INVALID_INPUT)
        }
        verifyNoInteractions(images)
    }

    @Test
    fun `a failed upload changes no value`() {
        val old = asset("kept")
        val member = member(image = old)
        val photo = photo(4, 5, 6)
        `when`(images.upload(photo)).thenThrow(BusinessException(ErrorCode.PROFILE_IMAGE_FAILED))

        val error = assertThrows<BusinessException> {
            edit(member, nickname = unique("실패"), action = ProfileImageAction.REPLACE, photo = photo)
        }

        assertThat(error.errorCode).isEqualTo(ErrorCode.PROFILE_IMAGE_FAILED)
        val saved = saved(member)
        assertThat(saved.nickname).isEqualTo(member.nickname)
        assertThat(saved.profileImageAsset?.id).isEqualTo(old.id)
    }

    @Test
    fun `a database failure after the upload deletes the new file and keeps the old image`() {
        val old = asset("safe")
        val taken = member()
        val member = member(image = old)
        val photo = photo(7, 8, 9)
        val uploaded = StoredImage(publicId("orphan"))
        `when`(images.upload(photo)).thenReturn(uploaded)

        val error = assertThrows<BusinessException> {
            edit(member, nickname = taken.nickname!!, action = ProfileImageAction.REPLACE, photo = photo)
        }

        assertThat(error.errorCode).isEqualTo(ErrorCode.NICKNAME_TAKEN)
        assertThat(saved(member).profileImageAsset?.id).isEqualTo(old.id)
        verify(images).delete(uploaded.objectKey)
        verify(images, never()).delete(old.objectKey)
    }

    @Test
    fun `a retried request ends in the same state and the first upload is released`() {
        val member = member()
        val photo = photo(1, 3, 5)
        val first = StoredImage(publicId("first"))
        val second = StoredImage(publicId("second"))
        `when`(images.upload(photo)).thenReturn(first, second)
        val nickname = unique("재시도")

        edit(member, nickname = nickname, action = ProfileImageAction.REPLACE, photo = photo)
        edit(member, nickname = nickname, action = ProfileImageAction.REPLACE, photo = photo)

        val saved = saved(member)
        assertThat(saved.nickname).isEqualTo(nickname)
        assertThat(saved.imageKey).isEqualTo(second.objectKey)
        verify(images, times(2)).upload(photo)
        verify(images).delete(first.objectKey)
        verify(images, never()).delete(second.objectKey)
    }

    @Test
    fun `the same final state sent twice changes nothing the second time`() {
        val member = member(image = asset("same"))
        val nickname = unique("같은")

        edit(member, nickname = nickname)
        val once = saved(member)
        edit(member, nickname = nickname)
        val twice = saved(member)

        assertThat(twice.nickname).isEqualTo(once.nickname)
        assertThat(twice.profileImageAsset?.id).isEqualTo(once.profileImageAsset?.id)
    }

    @Test
    fun `an unfinished signup is refused before any upload`() {
        val member = member(signup = SignupStatus.IN_PROGRESS)

        val error = assertThrows<BusinessException> {
            edit(member, action = ProfileImageAction.REPLACE, photo = photo(1, 2))
        }

        assertThat(error.errorCode).isEqualTo(ErrorCode.SIGNUP_INCOMPLETE)
        verifyNoInteractions(images)
    }

    @Test
    fun `suspended and withdrawn accounts are refused before any upload`() {
        listOf(AccountStatus.SUSPENDED, AccountStatus.WITHDRAWN).forEach { status ->
            val member = member(status = status)

            val error = assertThrows<BusinessException> {
                edit(member, action = ProfileImageAction.REPLACE, photo = photo(1, 2))
            }

            assertThat(error.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
        }
        verifyNoInteractions(images)
    }

    @Test
    fun `a new nickname shows in the bunch member list and old notification text is unchanged`() {
        val owner = member()
        val before = owner.nickname!!
        val bunch = bunches.create(
            owner.id,
            UUID.randomUUID(),
            CreateBunchRequest("번치" + hex(6), "함께 기록해요", Visibility.PUBLIC, CharacterType.TYPE_1)
        )
        val notification = notifications.save(
            Notification(
                recipientMemberId = owner.id,
                bunchId = bunch.id,
                type = NotificationType.MEMBER_JOINED,
                dedupeKey = "profile-edit:${UUID.randomUUID()}",
                title = "새 멤버가 들어왔어요",
                body = "${before}님이 번치에 참여했어요.",
                target = NotificationTarget(NotificationTargetKind.MEMBERS, bunch.id),
                createdAt = Instant.now()
            )
        )
        val after = unique("개명")

        edit(owner, nickname = after)

        assertThat(publicBunches.members(owner.id, bunch.id).single().nickname).isEqualTo(after)
        assertThat(notifications.findById(notification.id).get().body)
            .isEqualTo("${before}님이 번치에 참여했어요.")
    }

    private fun edit(
        member: Member,
        nickname: String = member.nickname!!,
        action: ProfileImageAction = ProfileImageAction.KEEP,
        photo: MultipartFile? = null,
        demographics: ProfileDemographics? = null
    ) = service.update(member.id, MemberProfileRequest(nickname, action, demographics), photo)

    // The member agrees to the optional consent, as the app does through the consent API.
    private fun consenting(member: Member): Member = member.also {
        consents.agreeToDemographics(it.id)
    }

    private fun member(
        nickname: String = unique("닉"),
        birthYear: Int? = null,
        gender: Gender? = null,
        image: ImageAsset? = null,
        signup: SignupStatus = SignupStatus.COMPLETED,
        status: AccountStatus = AccountStatus.ACTIVE
    ): Member = members.saveAndFlush(
        Member(
            signupStatus = signup,
            accountStatus = status,
            nickname = nickname,
            birthYear = birthYear,
            gender = gender,
            profileImageAsset = image
        )
    ).also { memberIds += it.id }

    private fun saved(member: Member): Member = members.findById(member.id).get()

    private fun asset(name: String): ImageAsset = assets.saveAndFlush(
        ImageAsset(objectKey = publicId(name))
    )

    private fun publicId(name: String): String = "profiles/$name-${UUID.randomUUID()}/profile.jpg".also { publicIds += it }

    private fun photo(vararg bytes: Byte) = MockMultipartFile("photo", "profile.jpg", "image/jpeg", bytes)

    private fun unique(prefix: String): String = prefix + hex(8)

    private fun hex(length: Int): String = UUID.randomUUID().toString().replace("-", "").take(length)
}
