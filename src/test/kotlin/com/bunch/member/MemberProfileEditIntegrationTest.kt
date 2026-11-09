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
import com.bunch.image.domain.ImageAsset
import com.bunch.image.domain.ImageAssetRepository
import com.bunch.member.domain.AccountStatus
import com.bunch.member.domain.Gender
import com.bunch.member.domain.Member
import com.bunch.member.domain.MemberConsentRepository
import com.bunch.member.domain.MemberRepository
import com.bunch.member.domain.SignupStatus
import com.bunch.member.dto.MemberProfileRequest
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
    private val members: MemberRepository,
    private val consents: MemberConsentRepository,
    private val assets: ImageAssetRepository,
    private val bunches: BunchService,
    private val publicBunches: BunchPublicService,
    private val notifications: NotificationRepository,
    private val jdbc: JdbcTemplate
) {
    @MockitoBean
    lateinit var images: ProfileImageStorage

    private val memberIds = mutableListOf<Long>()
    private val publicIds = mutableListOf<String>()

    @AfterEach
    fun cleanup() {
        memberIds.forEach { jdbc.update("DELETE FROM notifications WHERE recipient_member_id = ?", it) }
        memberIds.forEach { jdbc.update("DELETE FROM bunches WHERE created_by = ?", it) }
        memberIds.forEach { members.deleteById(it) }
        publicIds.forEach { jdbc.update("DELETE FROM image_assets WHERE public_id = ?", it) }
    }

    @Test
    fun `all four values are saved from the final state in one request`() {
        val member = member(birthYear = 1990, gender = Gender.MALE)
        val nickname = unique("새")

        edit(member, nickname = "  $nickname  ", birthYear = 1999, gender = Gender.FEMALE)

        val saved = saved(member)
        assertThat(saved.nickname).isEqualTo(nickname)
        assertThat(saved.birthYear).isEqualTo(1999)
        assertThat(saved.gender).isEqualTo(Gender.FEMALE)
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
    fun `a member may keep the own nickname while other values change`() {
        val member = member(birthYear = 1990)

        edit(member, birthYear = 1991)

        assertThat(saved(member).nickname).isEqualTo(member.nickname)
        assertThat(saved(member).birthYear).isEqualTo(1991)
    }

    @Test
    fun `a nickname of another member is rejected and nothing else changes`() {
        val other = member()
        val member = member(birthYear = 1990, gender = Gender.MALE)

        val error = assertThrows<BusinessException> {
            edit(member, nickname = other.nickname!!, birthYear = 2000, gender = Gender.FEMALE)
        }

        assertThat(error.errorCode).isEqualTo(ErrorCode.NICKNAME_TAKEN)
        val saved = saved(member)
        assertThat(saved.nickname).isEqualTo(member.nickname)
        assertThat(saved.birthYear).isEqualTo(1990)
        assertThat(saved.gender).isEqualTo(Gender.MALE)
        assertThat(saved.profileRequestKey).isNull()
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
    fun `birth year accepts the range of the specification and can be cleared`() {
        val member = member(birthYear = 1990)
        val latest = Year.now(ZoneId.of("Asia/Seoul")).value - 14

        edit(member, birthYear = 1930)
        assertThat(saved(member).birthYear).isEqualTo(1930)
        edit(member, birthYear = latest)
        assertThat(saved(member).birthYear).isEqualTo(latest)

        listOf(1929, latest + 1).forEach {
            assertThat(assertThrows<BusinessException> { edit(member, birthYear = it) }.errorCode)
                .isEqualTo(ErrorCode.INVALID_INPUT)
        }
        assertThat(saved(member).birthYear).isEqualTo(latest)

        edit(member, birthYear = null)
        assertThat(saved(member).birthYear).isNull()
    }

    @Test
    fun `gender can be cleared`() {
        val member = member(gender = Gender.FEMALE)

        edit(member, gender = null)

        assertThat(saved(member).gender).isNull()
    }

    @Test
    fun `birth year and gender need no analysis consent`() {
        val member = member()
        assertThat(consents.findAllByMemberId(member.id)).isEmpty()

        edit(member, birthYear = 1995, gender = Gender.MALE)

        assertThat(saved(member).birthYear).isEqualTo(1995)
        assertThat(saved(member).gender).isEqualTo(Gender.MALE)
    }

    @Test
    fun `resetting the image removes it and releases the old file after commit`() {
        val old = asset("old")
        val member = member(image = old)

        edit(member, action = ProfileImageAction.RESET)

        assertThat(saved(member).profileImageAsset).isNull()
        assertThat(assets.findById(old.id)).isEmpty
        verify(images).delete(old.publicId)
    }

    @Test
    fun `keeping the image changes neither the asset nor the file`() {
        val old = asset("keep")
        val member = member(image = old)

        edit(member, nickname = unique("유지"))

        assertThat(saved(member).profileImageAsset?.id).isEqualTo(old.id)
        verify(images, never()).delete(old.publicId)
    }

    @Test
    fun `replacing the image stores the new one and releases the old file after commit`() {
        val old = asset("before")
        val member = member(image = old)
        val photo = photo(1, 2, 3)
        val uploaded = StoredImage(publicId("after"), "https://res.cloudinary.com/test/after.jpg")
        `when`(images.upload(photo)).thenReturn(uploaded)

        edit(member, action = ProfileImageAction.REPLACE, photo = photo)

        assertThat(saved(member).imageUrl).isEqualTo(uploaded.url)
        assertThat(assets.findById(old.id)).isEmpty
        verify(images).delete(old.publicId)
        verify(images, never()).delete(uploaded.publicId)
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
        val member = member(image = old, birthYear = 1990)
        val photo = photo(4, 5, 6)
        `when`(images.upload(photo)).thenThrow(BusinessException(ErrorCode.PROFILE_IMAGE_FAILED))

        val error = assertThrows<BusinessException> {
            edit(member, nickname = unique("실패"), birthYear = 2000, action = ProfileImageAction.REPLACE, photo = photo)
        }

        assertThat(error.errorCode).isEqualTo(ErrorCode.PROFILE_IMAGE_FAILED)
        val saved = saved(member)
        assertThat(saved.nickname).isEqualTo(member.nickname)
        assertThat(saved.birthYear).isEqualTo(1990)
        assertThat(saved.profileImageAsset?.id).isEqualTo(old.id)
        assertThat(saved.profileRequestKey).isNull()
    }

    @Test
    fun `a database failure after the upload deletes the new file and keeps the old image`() {
        val old = asset("safe")
        val taken = member()
        val member = member(image = old)
        val photo = photo(7, 8, 9)
        val uploaded = StoredImage(publicId("orphan"), "https://res.cloudinary.com/test/orphan.jpg")
        `when`(images.upload(photo)).thenReturn(uploaded)

        val error = assertThrows<BusinessException> {
            edit(member, nickname = taken.nickname!!, action = ProfileImageAction.REPLACE, photo = photo)
        }

        assertThat(error.errorCode).isEqualTo(ErrorCode.NICKNAME_TAKEN)
        assertThat(saved(member).profileImageAsset?.id).isEqualTo(old.id)
        verify(images).delete(uploaded.publicId)
        verify(images, never()).delete(old.publicId)
    }

    @Test
    fun `a retried request is applied and uploaded once`() {
        val member = member()
        val photo = photo(1, 3, 5)
        val uploaded = StoredImage(publicId("once"), "https://res.cloudinary.com/test/once.jpg")
        `when`(images.upload(photo)).thenReturn(uploaded)
        val key = UUID.randomUUID()
        val nickname = unique("재시도")

        edit(member, nickname = nickname, action = ProfileImageAction.REPLACE, photo = photo, key = key)
        edit(member, nickname = nickname, action = ProfileImageAction.REPLACE, photo = photo, key = key)

        verify(images, times(1)).upload(photo)
        verify(images, never()).delete(uploaded.publicId)
        assertThat(saved(member).imageUrl).isEqualTo(uploaded.url)
    }

    @Test
    fun `the same key with other content conflicts while a new key is accepted`() {
        val member = member()
        val key = UUID.randomUUID()
        val nickname = unique("키")
        edit(member, nickname = nickname, key = key)

        val error = assertThrows<BusinessException> { edit(member, nickname = unique("다른"), key = key) }

        assertThat(error.errorCode).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT)
        assertThat(saved(member).nickname).isEqualTo(nickname)
        edit(member, nickname = unique("새키"))
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
        assertThat(notifications.findById(notification.id).get().body).isEqualTo("${before}님이 번치에 참여했어요.")
    }

    private fun edit(
        member: Member,
        nickname: String = member.nickname!!,
        birthYear: Int? = member.birthYear,
        gender: Gender? = member.gender,
        action: ProfileImageAction = ProfileImageAction.KEEP,
        photo: MultipartFile? = null,
        key: UUID = UUID.randomUUID()
    ) = service.update(member.id, key, MemberProfileRequest(nickname, birthYear, gender, action), photo)

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
        ImageAsset(publicId = publicId(name), url = "https://res.cloudinary.com/test/$name.jpg")
    )

    private fun publicId(name: String): String = "profiles/$name-${UUID.randomUUID()}".also { publicIds += it }

    private fun photo(vararg bytes: Byte) = MockMultipartFile("photo", "profile.jpg", "image/jpeg", bytes)

    private fun unique(prefix: String): String = prefix + hex(8)

    private fun hex(length: Int): String = UUID.randomUUID().toString().replace("-", "").take(length)
}
