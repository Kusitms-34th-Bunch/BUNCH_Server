package com.bunch.bunch

import com.bunch.bunch.domain.BunchAccessNoticeRepository
import com.bunch.bunch.domain.BunchFollowRepository
import com.bunch.bunch.domain.BunchFollowStatus
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.domain.ProfileImageAction
import com.bunch.bunch.domain.Visibility
import com.bunch.bunch.dto.BunchProfileRequest
import com.bunch.bunch.service.BunchHomeService
import com.bunch.bunch.service.BunchListService
import com.bunch.bunch.service.BunchProfileService
import com.bunch.bunch.service.BunchSocialService
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.image.ProfileImageStorage
import com.bunch.global.image.StoredImage
import com.bunch.notification.domain.Notification
import com.bunch.notification.domain.NotificationRepository
import com.bunch.notification.domain.NotificationTargetKind
import com.bunch.notification.domain.NotificationType
import com.bunch.notification.dto.NotificationTarget
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class BunchHomeProfileIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var profiles: BunchProfileService

    @Autowired
    private lateinit var home: BunchHomeService

    @Autowired
    private lateinit var list: BunchListService

    @Autowired
    private lateinit var social: BunchSocialService

    @Autowired
    private lateinit var follows: BunchFollowRepository

    @Autowired
    private lateinit var bunchRepository: BunchRepository

    @Autowired
    private lateinit var notices: BunchAccessNoticeRepository

    @Autowired
    private lateinit var notifications: NotificationRepository

    @MockitoBean
    private lateinit var images: ProfileImageStorage

    @AfterEach
    fun cleanProfileAssets() {
        jdbc.update(
            """UPDATE bunches SET profile_image_asset_id = NULL
                WHERE profile_image_asset_id IN (
                    SELECT id FROM image_assets WHERE public_id LIKE 'test-profile/%'
                )"""
        )
        jdbc.update("DELETE FROM image_assets WHERE public_id LIKE 'test-profile/%'")
    }

    @Test
    fun `owner profile save is versioned idempotent and excludes non owners`() {
        val owner = member()
        val bunch = create(owner)
        val member = member()
        join(member, issue(owner, bunch.id))
        val requestKey = UUID.randomUUID()
        val request = profile(
            name = "새 번치",
            introduction = "소개를 바꿔요",
            expectedVersion = 0
        )

        val first = profiles.update(owner, bunch.id, requestKey, request, null)
        assertThat(first.profileVersion).isEqualTo(1)
        assertThat(bunchRepository.findById(bunch.id).orElseThrow().name).isEqualTo("새 번치")

        val replay = profiles.update(owner, bunch.id, requestKey, request, null)
        assertThat(replay).isEqualTo(first)
        assertThat(bunchRepository.findById(bunch.id).orElseThrow().profileVersion).isEqualTo(1)
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM bunch_action_requests WHERE bunch_id = ? AND operation = 'PROFILE'",
            Long::class.java,
            bunch.id
        )).isEqualTo(1)

        assertError(ErrorCode.IDEMPOTENCY_CONFLICT) {
            profiles.update(owner, bunch.id, requestKey, request.copy(introduction = "다른 소개"), null)
        }
        assertError(ErrorCode.FORBIDDEN) {
            profiles.update(member, bunch.id, UUID.randomUUID(), request.copy(expectedProfileVersion = 1), null)
        }

        val noop = profiles.update(
            owner,
            bunch.id,
            UUID.randomUUID(),
            request.copy(expectedProfileVersion = 1),
            null
        )
        assertThat(noop.profileVersion).isEqualTo(1)
        assertThat(notifications.findAll()).noneMatch { it.type == NotificationType.BUNCH_VISIBILITY_CHANGED }
    }

    @Test
    fun `expired profile replay key is treated as a new request`() {
        val owner = member()
        val bunch = create(owner)
        val key = UUID.randomUUID()
        profiles.update(owner, bunch.id, key, profile("첫 이름", "첫 소개", 0), null)
        jdbc.update(
            "UPDATE bunch_action_requests SET created_at = CURRENT_TIMESTAMP - INTERVAL '2 days', " +
                "expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' " +
                "WHERE member_id = ? AND bunch_id = ? AND operation = 'PROFILE' AND request_key = ?",
            owner,
            bunch.id,
            key
        )

        val next = profiles.update(
            owner,
            bunch.id,
            key,
            profile("두 번째", "두 번째 소개", 1),
            null
        )

        assertThat(next.profileVersion).isEqualTo(2)
        assertThat(bunchRepository.findById(bunch.id).orElseThrow().introduction).isEqualTo("두 번째 소개")
    }

    @Test
    fun `profile rejects every KEEP or RESET photo part and bounds replace fingerprint reads`() {
        val owner = member()
        val bunch = create(owner)
        val empty = MockMultipartFile("photo", "empty.jpg", "image/jpeg", ByteArray(0))
        val oversized = MockMultipartFile(
            "photo",
            "large.jpg",
            "image/jpeg",
            ByteArray(5 * 1024 * 1024 + 1)
        )

        assertError(ErrorCode.INVALID_INPUT) {
            profiles.update(owner, bunch.id, UUID.randomUUID(), profile("유지", "소개", 0), empty)
        }
        assertError(ErrorCode.INVALID_INPUT) {
            profiles.update(
                owner,
                bunch.id,
                UUID.randomUUID(),
                profile("초기화", "소개", 0, imageAction = ProfileImageAction.RESET),
                empty
            )
        }
        assertError(ErrorCode.INVALID_INPUT) {
            profiles.update(
                owner,
                bunch.id,
                UUID.randomUUID(),
                profile("큰 사진", "소개", 0, imageAction = ProfileImageAction.REPLACE),
                oversized
            )
        }
        org.mockito.Mockito.verifyNoInteractions(images)
    }

    @Test
    fun `replace and reset reuse image assets while a failed save compensates the remote file`() {
        val owner = member()
        val bunch = create(owner, "사진 번치")
        val photo = MockMultipartFile("photo", "profile.jpg", "image/jpeg", byteArrayOf(1, 2, 3))
        val stored = StoredImage("test-profile/${UUID.randomUUID()}", "https://images.example/profile.jpg")
        `when`(images.upload(photo)).thenReturn(stored)

        val replaced = profiles.update(
            owner,
            bunch.id,
            UUID.randomUUID(),
            profile("사진 번치", "사진 소개", 0, imageAction = ProfileImageAction.REPLACE),
            photo
        )
        assertThat(replaced.profileVersion).isEqualTo(1)
        assertThat(bunchRepository.findById(bunch.id).orElseThrow().imagePublicId).isEqualTo(stored.publicId)

        val reset = profiles.update(
            owner,
            bunch.id,
            UUID.randomUUID(),
            profile("사진 번치", "사진 소개", 1, imageAction = ProfileImageAction.RESET),
            null
        )
        assertThat(reset.profileVersion).isEqualTo(2)
        assertThat(bunchRepository.findById(bunch.id).orElseThrow().imagePublicId).isNull()
        org.mockito.Mockito.verify(images).delete(stored.publicId)

        val rollbackBunch = create(owner, "보상 번치")
        val rollbackPhoto = MockMultipartFile("photo", "rollback.jpg", "image/jpeg", byteArrayOf(4, 5, 6))
        val rollbackStored = StoredImage(
            "test-profile/${UUID.randomUUID()}",
            "https://images.example/rollback.jpg"
        )
        `when`(images.upload(rollbackPhoto)).thenReturn(rollbackStored)
        rejectWrite("bunch_test_reject_image", "bunches", "UPDATE")
        try {
            assertThatThrownBy {
                profiles.update(
                    owner,
                    rollbackBunch.id,
                    UUID.randomUUID(),
                    profile("보상 번치", "실패 소개", 0, imageAction = ProfileImageAction.REPLACE),
                    rollbackPhoto
                )
            }.isInstanceOf(RuntimeException::class.java)
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS bunch_test_reject_image ON bunches")
            jdbc.execute("DROP FUNCTION IF EXISTS bunch_test_reject_write()")
        }
        org.mockito.Mockito.verify(images).delete(rollbackStored.publicId)
        assertThat(bunchRepository.findById(rollbackBunch.id).orElseThrow().profileVersion).isZero()
        assertThat(bunchRepository.findById(rollbackBunch.id).orElseThrow().imagePublicId).isNull()
    }

    @Test
    fun `private public round trip approves pending follow and writes directional visibility notices`() {
        val targetOwner = member()
        val target = create(targetOwner)
        val targetMember = member()
        join(targetMember, issue(targetOwner, target.id))
        val followerOwner = member()
        val follower = create(followerOwner)
        val pending = social.follow(followerOwner, follower.id, target.id)
        val followId = pending.followId!!

        val publicResult = profiles.update(
            targetOwner,
            target.id,
            UUID.randomUUID(),
            profile("공개 번치", "공개 소개", 0, Visibility.PUBLIC),
            null
        )

        assertThat(publicResult.profileVersion).isEqualTo(1)
        assertThat(follows.findById(followId).orElseThrow().status).isEqualTo(BunchFollowStatus.APPROVED)
        val publicVisibilityNotification =
            recipientNotifications(targetMember, target.id, NotificationType.BUNCH_VISIBILITY_CHANGED).single()
        assertThat(publicVisibilityNotification.title).isEqualTo("우리 번치가 공개로 바뀌었어요")
        assertThat(publicVisibilityNotification.body).isEqualTo("이제 누구나 우리 번치와 번들을 볼 수 있어요.")
        assertThat(recipientNotifications(targetOwner, target.id, NotificationType.BUNCH_VISIBILITY_CHANGED)).isEmpty()
        assertThat(recipientNotifications(followerOwner, follower.id, NotificationType.FOLLOW_REQUEST_APPROVED))
            .extracting<String> { it.dedupeKey }
            .containsExactly("FOLLOW_REQUEST_APPROVED:$followId")

        val privateResult = profiles.update(
            targetOwner,
            target.id,
            UUID.randomUUID(),
            profile("공개 번치", "공개 소개", 1, Visibility.PRIVATE),
            null
        )

        assertThat(privateResult.profileVersion).isEqualTo(2)
        assertThat(recipientNotifications(targetMember, target.id, NotificationType.BUNCH_VISIBILITY_CHANGED))
            .extracting<String> { it.body }
            .containsExactlyInAnyOrder(
                "이제 누구나 우리 번치와 번들을 볼 수 있어요.",
                "이제 팔로우를 승인한 번치만 우리 번치와 번들을 볼 수 있어요."
            )
        assertThat(recipientNotifications(targetOwner, target.id, NotificationType.BUNCH_VISIBILITY_CHANGED)).isEmpty()
    }

    @Test
    fun `visibility and notifications roll back together`() {
        val targetOwner = member()
        val target = create(targetOwner)
        val followerOwner = member()
        val follower = create(followerOwner)
        val followId = social.follow(followerOwner, follower.id, target.id).followId!!
        rejectWrite("bunch_test_reject_notification", "notifications", "INSERT")
        try {
            assertThatThrownBy {
                profiles.update(
                    targetOwner,
                    target.id,
                    UUID.randomUUID(),
                    profile("롤백", "롤백 소개", 0, Visibility.PUBLIC),
                    null
                )
            }.isInstanceOf(RuntimeException::class.java)
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS bunch_test_reject_notification ON notifications")
            jdbc.execute("DROP FUNCTION IF EXISTS bunch_test_reject_write()")
        }

        assertThat(bunchRepository.findById(target.id).orElseThrow().visibility).isEqualTo(Visibility.PRIVATE)
        assertThat(bunchRepository.findById(target.id).orElseThrow().profileVersion).isZero()
        assertThat(follows.findById(followId).orElseThrow().status).isEqualTo(BunchFollowStatus.PENDING)
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM bunch_action_requests WHERE bunch_id = ? AND operation = 'PROFILE'",
            Long::class.java,
            target.id
        )).isZero()
    }

    @Test
    fun `concurrent profile writes serialize on the target bunch version`() {
        val owner = member()
        val bunch = create(owner)
        val results = concurrently(
            listOf(
                {
                    profiles.update(
                        owner,
                        bunch.id,
                        UUID.randomUUID(),
                        profile("동시 A", "소개 A", 0),
                        null
                    )
                },
                {
                    profiles.update(
                        owner,
                        bunch.id,
                        UUID.randomUUID(),
                        profile("동시 B", "소개 B", 0),
                        null
                    )
                }
            )
        )

        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(results.single { it.isFailure }.exceptionOrNull())
            .isInstanceOf(BusinessException::class.java)
            .extracting<ErrorCode> { (it as BusinessException).errorCode }
            .isEqualTo(ErrorCode.BUNCH_PROFILE_VERSION_CONFLICT)
        assertThat(bunchRepository.findById(bunch.id).orElseThrow().profileVersion).isEqualTo(1)
    }

    @Test
    fun `public transition and block race never leaves a pending relation`() {
        val targetOwner = member()
        val target = create(targetOwner)
        val followerOwner = member()
        val follower = create(followerOwner)
        social.follow(followerOwner, follower.id, target.id)

        val results = concurrently(
            listOf(
                {
                    profiles.update(
                        targetOwner,
                        target.id,
                        UUID.randomUUID(),
                        profile("경합 공개", "경합 소개", 0, Visibility.PUBLIC),
                        null
                    )
                },
                {
                    social.block(followerOwner, follower.id, target.id)
                }
            )
        )

        assertThat(results).allMatch { it.isSuccess }
        assertThat(follows.findByFollowerBunchIdAndFollowedBunchId(follower.id, target.id)?.status)
            .isNotEqualTo(BunchFollowStatus.PENDING)
    }

    @Test
    fun `home and list use actual counts ordering and access notice priority`() {
        val owner = member()
        val first = create(owner, "첫 번치")
        val second = create(owner, "둘째 번치")
        val target = create(owner, "홈 번치")
        val targetMember = member()
        join(targetMember, issue(owner, target.id))
        jdbc.update(
            "UPDATE bunch_memberships SET last_used_at = ? WHERE member_id = ? AND bunch_id = ?",
            Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")), owner, first.id
        )
        jdbc.update(
            "UPDATE bunch_memberships SET last_used_at = ? WHERE member_id = ? AND bunch_id = ?",
            Timestamp.from(Instant.parse("2026-01-03T00:00:00Z")), owner, second.id
        )
        jdbc.update(
            "UPDATE bunch_memberships SET last_used_at = ? WHERE member_id = ? AND bunch_id = ?",
            Timestamp.from(Instant.parse("2026-01-02T00:00:00Z")), owner, target.id
        )

        profiles.update(owner, target.id, UUID.randomUUID(), profile("홈 공개", "홈 소개", 0, Visibility.PUBLIC), null)
        val otherOwner = member()
        val other = create(otherOwner, "팔로잉 대상")
        profiles.update(otherOwner, other.id, UUID.randomUUID(), profile("팔로잉 공개", "소개", 0, Visibility.PUBLIC), null)
        social.follow(otherOwner, other.id, target.id)
        social.follow(owner, target.id, other.id)
        insertBundle(target.id, owner, "DRAFT", null)
        insertBundle(target.id, owner, "PUBLISHED", Visibility.PUBLIC)
        insertBundle(target.id, owner, "PUBLISHED", Visibility.PRIVATE)
        notifications.saveAndFlush(
            Notification(
                recipientMemberId = owner,
                bunchId = target.id,
                type = NotificationType.BUNCH_VISIBILITY_CHANGED,
                dedupeKey = "home-unread-${UUID.randomUUID()}",
                title = "확인할 알림",
                body = "확인할 알림이 있어요.",
                target = NotificationTarget(NotificationTargetKind.BUNCH_HOME, target.id),
                createdAt = Instant.now()
            )
        )

        val homeResponse = home.home(owner, target.id)
        assertThat(homeResponse.memberCount).isEqualTo(2)
        assertThat(homeResponse.followerCount).isEqualTo(1)
        assertThat(homeResponse.followingCount).isEqualTo(1)
        assertThat(homeResponse.draftCount).isEqualTo(1)
        assertThat(homeResponse.publishedCount).isEqualTo(2)
        assertThat(homeResponse.stage).isEqualTo(2)
        assertThat(homeResponse.unreadCount).isGreaterThanOrEqualTo(1)
        assertThat(homeResponse.permissions.canEditProfile).isTrue()

        val listResponse = list.list(owner, target.id)
        assertThat(listResponse.activeCount).isEqualTo(3)
        assertThat(listResponse.defaultBunchId).isEqualTo(second.id)
        assertThat(listResponse.activeBunches.map { it.bunch.id })
            .startsWith(target.id)
            .containsExactly(target.id, second.id, first.id)
    }

    @Test
    fun `home returns access unavailable before target lookup for a removed member`() {
        val owner = member()
        val bunch = create(owner, "접근 안내 번치")
        val removed = member()
        join(removed, issue(owner, bunch.id))
        val membershipId = jdbc.queryForObject(
            "SELECT id FROM bunch_memberships WHERE member_id = ? AND bunch_id = ? AND status = 'ACTIVE'",
            Long::class.java,
            removed,
            bunch.id
        )!!
        jdbc.update(
            "UPDATE bunch_memberships SET status = 'LEFT', ended_at = CURRENT_TIMESTAMP WHERE id = ?",
            membershipId
        )
        val noticeId = jdbc.queryForObject(
            """INSERT INTO bunch_access_notices
                (recipient_member_id, bunch_id, membership_id, bunch_name, character_type, reason, occurred_at, expires_at)
                VALUES (?, ?, ?, ?, 'TYPE_2', 'REMOVED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '30 days')
                RETURNING id""",
            Long::class.java,
            removed,
            bunch.id,
            membershipId,
            "접근 안내 번치"
        )!!

        val error = org.assertj.core.api.Assertions.catchThrowable { home.home(removed, bunch.id) }
        assertThat(error).isInstanceOf(BusinessException::class.java)
        assertThat((error as BusinessException).errorCode).isEqualTo(ErrorCode.BUNCH_ACCESS_UNAVAILABLE)
        assertThat(error.detail).isEqualTo(mapOf("noticeId" to noticeId, "reason" to "REMOVED"))
        assertThat(notices.findById(noticeId)).isPresent
    }

    private fun profile(
        name: String,
        introduction: String,
        expectedVersion: Long,
        visibility: Visibility = Visibility.PRIVATE,
        imageAction: ProfileImageAction = ProfileImageAction.KEEP
    ) = BunchProfileRequest(name, introduction, visibility, imageAction, expectedVersion)

    private fun recipientNotifications(memberId: Long, bunchId: Long, type: NotificationType): List<Notification> =
        notifications.findAll().filter {
            it.recipientMemberId == memberId && it.bunchId == bunchId && it.type == type
        }

    private fun insertBundle(
        bunchId: Long,
        createdBy: Long,
        status: String,
        visibility: Visibility?
    ): Long = jdbc.queryForObject(
        """INSERT INTO bundles
            (bunch_id, created_by, status, title, visibility, created_at, updated_at, published_at)
            VALUES (?, ?, ?, 'fixture', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?)
            RETURNING id""",
        Long::class.java,
        bunchId,
        createdBy,
        status,
        visibility?.name,
        if (status == "PUBLISHED") Timestamp.from(Instant.now()) else null
    )!!
}
