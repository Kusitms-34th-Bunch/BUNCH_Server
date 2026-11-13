package com.bunch.member

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.dto.OwnerTransferRequest
import com.bunch.bunch.service.BunchMembershipManagementService
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.service.BundleAttributionService
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.image.PhotoImageStorage
import com.bunch.global.image.ProfileImageStorage
import com.bunch.global.oidc.AuthProvider
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.image.domain.ImageAsset
import com.bunch.image.domain.ImageAssetRepository
import com.bunch.member.domain.ExternalIdentity
import com.bunch.member.domain.ExternalIdentityRepository
import com.bunch.member.dto.WithdrawalOwnerBunch
import com.bunch.member.service.MemberWithdrawalService
import com.bunch.member.service.SocialMemberRegistry
import com.bunch.notification.domain.Notification
import com.bunch.notification.domain.NotificationRepository
import com.bunch.notification.domain.NotificationTargetKind
import com.bunch.notification.domain.NotificationType
import com.bunch.notification.dto.NotificationTarget
import com.bunch.photo.domain.DateSource
import com.bunch.photo.domain.Photo
import com.bunch.photo.domain.PhotoRepository
import com.bunch.photo.domain.PhotoUploader
import com.bunch.photo.domain.PhotoUploaderRepository
import com.bunch.photo.domain.ProcessingStatus
import com.bunch.social.dto.CreateSocialCommentRequest
import com.bunch.social.service.SocialCommentService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.Instant
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class MemberWithdrawalIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var withdrawals: MemberWithdrawalService

    @Autowired
    private lateinit var management: BunchMembershipManagementService

    @Autowired
    private lateinit var socialComments: SocialCommentService

    @Autowired
    private lateinit var attribution: BundleAttributionService

    @Autowired
    private lateinit var registry: SocialMemberRegistry

    @Autowired
    private lateinit var sessions: RefreshTokenRepository

    @Autowired
    private lateinit var identities: ExternalIdentityRepository

    @Autowired
    private lateinit var memberships: BunchMembershipRepository

    @Autowired
    private lateinit var bunchRepository: BunchRepository

    @Autowired
    private lateinit var bundleRepository: BundleRepository

    @Autowired
    private lateinit var assets: ImageAssetRepository

    @Autowired
    private lateinit var photos: PhotoRepository

    @Autowired
    private lateinit var uploaders: PhotoUploaderRepository

    @Autowired
    private lateinit var notificationRepository: NotificationRepository

    @MockitoBean
    private lateinit var profileImages: ProfileImageStorage

    @MockitoBean
    private lateinit var photoImages: PhotoImageStorage

    private val sessionIds = mutableListOf<Pair<String, Long>>()
    private val extraMemberIds = mutableListOf<Long>()

    @AfterEach
    fun cleanWithdrawalFixtures() {
        jdbc.execute("DROP TRIGGER IF EXISTS withdrawal_test_reject ON members")
        jdbc.execute("DROP FUNCTION IF EXISTS withdrawal_test_reject_write()")
        sessionIds.forEach { (sessionId, memberId) -> sessions.delete(sessionId, memberId) }
        sessionIds.clear()
        jdbc.update("DELETE FROM social_comments")
        jdbc.update("DELETE FROM push_jobs")
        jdbc.update("DELETE FROM push_devices")
        jdbc.update("DELETE FROM push_preferences")
        jdbc.update("DELETE FROM bunch_access_notices")
        jdbc.update("DELETE FROM bunch_action_requests")
        jdbc.update("DELETE FROM bundle_comments")
        jdbc.update("DELETE FROM bundle_participants")
        jdbc.update("DELETE FROM bundle_photos")
        jdbc.update("DELETE FROM bundle_requests")
        jdbc.update("DELETE FROM bundles")
        jdbc.update("DELETE FROM photo_uploaders")
        jdbc.update("DELETE FROM photos")
        jdbc.update("DELETE FROM external_identity WHERE provider_subject LIKE 'withdrawal-test-%'")
        extraMemberIds.forEach { jdbc.update("DELETE FROM members WHERE id = ?", it) }
        extraMemberIds.clear()
        jdbc.update("DELETE FROM image_assets WHERE public_id LIKE 'withdrawal-test/%'")
    }

    @Test
    fun `nothing about the person remains but the account row and the consent history stay`() {
        val memberId = member()
        val image = asset("profile")
        jdbc.update(
            "UPDATE members SET birth_year = 1999, gender = 'FEMALE', profile_image_asset_id = ?, " +
                "registration_fingerprint = ? WHERE id = ?",
            image.id, "a".repeat(64), memberId
        )
        jdbc.update(
            "INSERT INTO member_consents(member_id, type, version, agreed_at) VALUES (?, 'SERVICE', 'v1', now())",
            memberId
        )
        linkIdentity(memberId)

        withdrawals.withdraw(memberId)

        val row = jdbc.queryForMap(
            "SELECT account_status, signup_status, nickname, birth_year, gender, profile_image_asset_id, " +
                "registration_fingerprint FROM members WHERE id = ?",
            memberId
        )
        assertThat(row["account_status"]).isEqualTo("WITHDRAWN")
        assertThat(row["signup_status"]).isEqualTo("COMPLETED")
        listOf(
            "nickname", "birth_year", "gender", "profile_image_asset_id", "registration_fingerprint"
        ).forEach { assertThat(row[it]).describedAs(it).isNull() }
        assertThat(identityCount(memberId)).isZero()
        assertThat(count("member_consents WHERE member_id = $memberId")).isEqualTo(1)
        assertThat(assets.findById(image.id)).isEmpty
        verify(profileImages).delete(image.publicId)
    }

    @Test
    fun `the same social account signs up again as a new account`() {
        val memberId = member()
        val subject = linkIdentity(memberId)

        withdrawals.withdraw(memberId)
        val again = registry.findOrCreate(AuthProvider.KAKAO, subject)
        extraMemberIds += again.id

        assertThat(again.id).isNotEqualTo(memberId)
        assertThat(status(again.id)).isEqualTo("ACTIVE")
        assertThat(status(memberId)).isEqualTo("WITHDRAWN")
    }

    @Test
    fun `a member and a co owner leave every bunch without handing anything over and nobody is told`() {
        val owner = member()
        val self = member()
        val first = create(owner)
        join(self, issue(owner, first.id))
        val second = create(owner)
        join(self, issue(owner, second.id))
        jdbc.update(
            "UPDATE bunch_memberships SET role = 'CO_OWNER' WHERE bunch_id = ? AND member_id = ?",
            second.id,
            self
        )
        val told = notificationCount(owner)

        withdrawals.withdraw(self)

        assertThat(membershipStatus(self, first.id)).isEqualTo("LEFT")
        assertThat(membershipStatus(self, second.id)).isEqualTo("LEFT")
        assertThat(membershipStatus(owner, first.id)).isEqualTo("ACTIVE")
        assertThat(notificationCount(owner)).isEqualTo(told)
        assertThat(bunchExists(first.id)).isTrue()
        assertThat(bunchExists(second.id)).isTrue()
    }

    @Test
    fun `a main manager with other members is refused with the bunches to hand over and nothing changes`() {
        val manager = member()
        val guest = member()
        val lower = create(guest)
        join(manager, issue(guest, lower.id))
        val higher = create(manager)
        join(guest, issue(manager, higher.id))
        val sole = create(manager)
        device(manager)
        val session = session(manager)

        val error = catchThrowable { withdrawals.withdraw(manager) }

        assertThat(error).isInstanceOf(BusinessException::class.java)
        val failure = error as BusinessException
        assertThat(failure.errorCode).isEqualTo(ErrorCode.MEMBER_WITHDRAWAL_OWNER_REQUIRED)
        val listed = (failure.detail as Map<*, *>)["bunches"] as List<*>
        assertThat(listed.map { (it as WithdrawalOwnerBunch).bunchId }).containsExactly(higher.id)
        assertThat(status(manager)).isEqualTo("ACTIVE")
        assertThat(membershipStatus(manager, lower.id)).isEqualTo("ACTIVE")
        assertThat(membershipStatus(manager, higher.id)).isEqualTo("ACTIVE")
        assertThat(bunchExists(sole.id)).isTrue()
        assertThat(count("push_devices WHERE member_id = $manager")).isEqualTo(1)
        assertThat(sessions.isActive(session, manager)).isTrue()
    }

    @Test
    fun `a main manager alone in a bunch deletes it with the account while other bunches are only left`() {
        val self = member()
        val guest = member()
        val alone = create(self)
        val shared = create(guest)
        join(self, issue(guest, shared.id))

        withdrawals.withdraw(self)

        assertThat(bunchExists(alone.id)).isFalse()
        assertThat(bunchExists(shared.id)).isTrue()
        assertThat(membershipStatus(self, shared.id)).isEqualTo("LEFT")
        assertThat(membershipStatus(guest, shared.id)).isEqualTo("ACTIVE")
    }

    @Test
    fun `a main manager who handed the bunch over can withdraw and the new manager keeps it`() {
        val manager = member()
        val guest = member()
        val bunch = create(manager)
        join(guest, issue(manager, bunch.id))
        val own = memberships.findByMemberIdAndBunchIdAndStatus(manager, bunch.id)!!
        val target = memberships.findByMemberIdAndBunchIdAndStatus(guest, bunch.id)!!
        management.transferOwner(
            manager,
            bunch.id,
            UUID.randomUUID(),
            OwnerTransferRequest(own.id, membershipVersion(bunch.id), target.id)
        )

        withdrawals.withdraw(manager)

        assertThat(bunchExists(bunch.id)).isTrue()
        assertThat(membershipStatus(manager, bunch.id)).isEqualTo("LEFT")
        assertThat(jdbc.queryForObject(
            "SELECT role FROM bunch_memberships WHERE member_id = ? AND bunch_id = ?",
            String::class.java,
            guest,
            bunch.id
        )).isEqualTo("OWNER")
    }

    @Test
    fun `a failure while the account is changed rolls back everything and keeps every session`() {
        val self = member()
        val guest = member()
        val alone = create(self)
        val shared = create(guest)
        join(self, issue(guest, shared.id))
        val sharedPhoto = photo(shared.id, listOf(self))
        linkIdentity(self)
        device(self)
        val session = session(self)
        jdbc.execute(
            "CREATE FUNCTION withdrawal_test_reject_write() RETURNS trigger LANGUAGE plpgsql AS " +
                "'BEGIN RAISE EXCEPTION ''injected withdrawal failure''; END'"
        )
        jdbc.execute(
            "CREATE TRIGGER withdrawal_test_reject BEFORE UPDATE ON members " +
                "FOR EACH ROW EXECUTE FUNCTION withdrawal_test_reject_write()"
        )

        assertThatThrownBy { withdrawals.withdraw(self) }.isInstanceOf(RuntimeException::class.java)

        assertThat(status(self)).isEqualTo("ACTIVE")
        assertThat(bunchExists(alone.id)).isTrue()
        assertThat(membershipStatus(self, shared.id)).isEqualTo("ACTIVE")
        assertThat(count("photo_uploaders WHERE photo_id = ${sharedPhoto.id}")).isEqualTo(1)
        assertThat(identityCount(self)).isEqualTo(1)
        assertThat(count("push_devices WHERE member_id = $self")).isEqualTo(1)
        assertThat(sessions.isActive(session, self)).isTrue()
    }

    @Test
    fun `every session of the account on every device goes after the commit and others stay`() {
        val self = member()
        val other = member()
        val sessionsOfSelf = (1..3).map { session(self) }
        val sessionOfOther = session(other)

        withdrawals.withdraw(self)

        sessionsOfSelf.forEach { assertThat(sessions.isActive(it, self)).describedAs(it).isFalse() }
        assertThat(sessions.isActive(sessionOfOther, other)).isTrue()
    }

    @Test
    fun `push devices settings inbox and notices of the member go and nobody else loses any`() {
        val self = member()
        val guest = member()
        val bunch = create(guest)
        join(self, issue(guest, bunch.id))
        listOf(self, guest).forEach {
            device(it)
            preference(it)
            notification(it, bunch.id)
            accessNotice(it, bunch.id)
            pendingJob(it, bunch.id)
        }
        val inboxOfGuest = notificationCount(guest)

        withdrawals.withdraw(self)

        listOf("push_devices", "push_preferences", "notifications", "bunch_access_notices").forEach {
            val column = if (it == "push_devices" || it == "push_preferences") "member_id" else "recipient_member_id"
            assertThat(count("$it WHERE $column = $self")).describedAs(it).isZero()
        }
        assertThat(count("push_jobs WHERE recipient_member_id = $self")).isZero()
        assertThat(count("push_devices WHERE member_id = $guest")).isEqualTo(1)
        assertThat(count("push_preferences WHERE member_id = $guest")).isEqualTo(1)
        assertThat(count("bunch_access_notices WHERE recipient_member_id = $guest")).isEqualTo(1)
        assertThat(count("push_jobs WHERE recipient_member_id = $guest")).isEqualTo(1)
        assertThat(notificationCount(guest)).isEqualTo(inboxOfGuest)
    }

    @Test
    fun `uploaded photos leave every photo box and shared ones lose only the member`() {
        val self = member()
        val guest = member()
        val bunch = create(guest)
        join(self, issue(guest, bunch.id))
        val own = photo(bunch.id, listOf(self))
        val shared = photo(bunch.id, listOf(self, guest))
        val others = photo(bunch.id, listOf(guest))

        withdrawals.withdraw(self)

        assertThat(photos.findById(own.id)).isEmpty
        assertThat(photos.findById(shared.id)).isPresent
        assertThat(uploaders.findAll().filter { it.photo.id == shared.id }.map { it.member.id }).containsExactly(guest)
        assertThat(photos.findById(others.id)).isPresent
    }

    @Test
    fun `drafts the member created are deleted and the member leaves other drafts with the comments`() {
        val self = member()
        val guest = member()
        val bunch = create(guest)
        join(self, issue(guest, bunch.id))
        val mine = draft(bunch.id, self, listOf(self, guest))
        val theirs = draft(bunch.id, guest, listOf(guest, self))
        comment(mine, self, "내 초안의 코멘트")
        comment(theirs, self, "남의 초안에 쓴 코멘트")
        comment(theirs, guest, "남이 쓴 코멘트")

        withdrawals.withdraw(self)

        assertThat(bundleRepository.findById(mine)).isEmpty
        assertThat(bundleRepository.findById(theirs)).isPresent
        assertThat(participantIds(theirs)).containsExactly(guest)
        assertThat(commentContents(theirs)).containsExactly("남이 쓴 코멘트")
    }

    @Test
    fun `comments in published bundles lose their text and read as deleted while participants stay`() {
        val self = member()
        val guest = member()
        val bunch = create(guest)
        join(self, issue(guest, bunch.id))
        val bundle = published(bunch.id, guest)
        listOf(self, guest).forEach { participant(bundle, it) }
        comment(bundle, self, "탈퇴한 사람의 코멘트")
        comment(bundle, guest, "남아 있는 코멘트")

        withdrawals.withdraw(self)

        assertThat(commentContents(bundle)).containsExactlyInAnyOrder("", "남아 있는 코멘트")
        val shown = attribution.published(bundleRepository.findById(bundle).orElseThrow())
        assertThat(shown.participants).hasSize(2)
        val gone = shown.participants.single { it.memberId == self }
        assertThat(gone.nickname).isEqualTo("작성자")
        assertThat(gone.imageUrl).isNull()
        assertThat(gone.profileAvailable).isFalse()
        assertThat(shown.comments.single { it.memberId == self }.content).isEqualTo("삭제된 코멘트입니다")
        assertThat(shown.comments.single { it.memberId == guest }.content).isEqualTo("남아 있는 코멘트")
    }

    @Test
    fun `comments and replies read as deleted ones with the replies and the counts kept`() {
        val writer = member()
        publicBunch(writer)
        val owner = member()
        val target = publicBunch(owner)
        val bundle = published(target, owner)
        val rootOfWriter = socialComment(writer, bundle, "작성자의 댓글")
        val replyOfOwner = socialComment(owner, bundle, "주인의 답글", rootOfWriter)
        val rootOfOwner = socialComment(owner, bundle, "주인의 댓글")
        val replyOfWriter = socialComment(writer, bundle, "작성자의 답글", rootOfOwner)
        val before = socialComments.list(owner, bundle)

        withdrawals.withdraw(writer)

        val page = socialComments.list(owner, bundle)
        assertThat(page.totalCount).isEqualTo(before.totalCount)
        val hidden = page.items.single { it.id == rootOfWriter }
        assertThat(hidden.deleted).isTrue()
        assertThat(hidden.content).isEqualTo("삭제된 댓글입니다")
        assertThat(hidden.author.nickname).isEqualTo("작성자")
        assertThat(hidden.author.memberId).isNull()
        assertThat(hidden.author.imageUrl).isNull()
        assertThat(hidden.createdAt).isNull()
        assertThat(hidden.badge).isNull()
        assertThat(hidden.canReply).isFalse()
        assertThat(hidden.replyCount).isEqualTo(1)
        assertThat(page.items.single { it.id == rootOfOwner }.deleted).isFalse()
        assertThat(socialComments.list(owner, bundle, rootOfWriter).items.single { it.id == replyOfOwner }.content)
            .isEqualTo("주인의 답글")
        val hiddenReply = socialComments.list(owner, bundle, rootOfOwner).items.single { it.id == replyOfWriter }
        assertThat(hiddenReply.deleted).isTrue()
        assertThat(hiddenReply.content).isEqualTo("삭제된 댓글입니다")
        assertThat(hiddenReply.author.nickname).isEqualTo("작성자")
        assertThat(
            jdbc.queryForList(
                "SELECT content FROM social_comments WHERE author_member_id = ?",
                String::class.java,
                writer
            )
        ).containsOnly("")
        assertThat(count("social_comments WHERE author_member_id = $owner AND deleted_at IS NULL")).isEqualTo(2)
    }

    @Test
    fun `an account that is not active or not completed cannot withdraw`() {
        val unfinished = member(completed = false)
        val withdrawn = member()
        withdrawals.withdraw(withdrawn)

        assertError(ErrorCode.SIGNUP_INCOMPLETE) { withdrawals.withdraw(unfinished) }
        assertError(ErrorCode.UNAUTHORIZED) { withdrawals.withdraw(withdrawn) }
        assertError(ErrorCode.USER_NOT_FOUND) { withdrawals.withdraw(Long.MAX_VALUE) }
    }

    @Test
    fun `two requests at once withdraw the account once and the other is refused`() {
        val self = member()
        val guest = member()
        val shared = create(guest)
        join(self, issue(guest, shared.id))

        val results = concurrently(List(2) { { withdrawals.withdraw(self) } })

        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        val failure = results.single { it.isFailure }.exceptionOrNull() as BusinessException
        assertThat(failure.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
        assertThat(status(self)).isEqualTo("WITHDRAWN")
    }

    private fun status(memberId: Long): String =
        jdbc.queryForObject("SELECT account_status FROM members WHERE id = ?", String::class.java, memberId)!!

    private fun membershipStatus(memberId: Long, bunchId: Long): String = jdbc.queryForObject(
        "SELECT status FROM bunch_memberships WHERE member_id = ? AND bunch_id = ?",
        String::class.java,
        memberId,
        bunchId
    )!!

    private fun bunchExists(bunchId: Long): Boolean = count("bunches WHERE id = $bunchId") == 1L

    private fun membershipVersion(bunchId: Long): Long = jdbc.queryForObject(
        "SELECT membership_version FROM bunches WHERE id = ?",
        Long::class.java,
        bunchId
    )!!

    private fun notificationCount(memberId: Long): Long =
        count("notifications WHERE recipient_member_id = $memberId")

    private fun identityCount(memberId: Long): Long = count("external_identity WHERE member_id = $memberId")

    private fun linkIdentity(memberId: Long): String {
        val subject = "withdrawal-test-${UUID.randomUUID()}"
        identities.saveAndFlush(
            ExternalIdentity(
                member = members.findById(memberId).orElseThrow(),
                provider = AuthProvider.KAKAO,
                providerSubject = subject
            )
        )
        return subject
    }

    private fun session(memberId: Long): String {
        val sessionId = UUID.randomUUID().toString()
        sessions.save(sessionId, memberId, "refresh-${UUID.randomUUID()}", 3600)
        sessionIds += sessionId to memberId
        return sessionId
    }

    private fun asset(name: String): ImageAsset = assets.saveAndFlush(
        ImageAsset(publicId = "withdrawal-test/$name-${UUID.randomUUID()}", url = "https://images.example/$name.jpg")
    )

    private fun device(memberId: Long) {
        val installation = UUID.randomUUID()
        jdbc.update(
            """INSERT INTO push_devices(installation_id,member_id,session_id,environment,topic,
                token_ciphertext,token_hash,authorization_status,registered_at)
                VALUES (?,?,?,'SANDBOX','test.bunch',?,?,'AUTHORIZED',CURRENT_TIMESTAMP)""",
            installation,
            memberId,
            UUID.randomUUID(),
            byteArrayOf(1, 2, 3),
            installation.toString().replace("-", "").repeat(2)
        )
    }

    private fun preference(memberId: Long) {
        jdbc.update(
            "INSERT INTO push_preferences(member_id, enabled, categories, updated_at) " +
                "VALUES (?, true, '{}'::jsonb, CURRENT_TIMESTAMP)",
            memberId
        )
    }

    private fun notification(memberId: Long, bunchId: Long) {
        notificationRepository.saveAndFlush(
            Notification(
                recipientMemberId = memberId,
                bunchId = bunchId,
                type = NotificationType.MEMBER_JOINED,
                dedupeKey = "withdrawal-test:${UUID.randomUUID()}",
                title = "새 멤버가 들어왔어요",
                body = "함께해요.",
                target = NotificationTarget(NotificationTargetKind.MEMBERS, bunchId),
                createdAt = Instant.now()
            )
        )
    }

    private fun accessNotice(memberId: Long, bunchId: Long) {
        jdbc.update(
            """INSERT INTO bunch_access_notices(recipient_member_id,bunch_id,membership_id,bunch_name,character_type,
                reason,occurred_at,expires_at)
                VALUES (?,?,?,'공지 번치','TYPE_1','REMOVED',now(),now() + interval '1 day')""",
            memberId,
            bunchId,
            Math.abs(UUID.randomUUID().leastSignificantBits % 1_000_000_000)
        )
    }

    private fun pendingJob(memberId: Long, bunchId: Long) {
        jdbc.update(
            """INSERT INTO push_jobs(id,recipient_member_id,session_id,bunch_id,dedupe_key,type,payload,status,
                available_at,expires_at,created_at)
                VALUES (?,?,?,?,?,'MEMBER_JOINED','{}'::jsonb,'PENDING',now(),now() + interval '1 day',now())""",
            UUID.randomUUID(),
            memberId,
            UUID.randomUUID(),
            bunchId,
            "withdrawal-test:${UUID.randomUUID()}"
        )
    }

    private fun photo(bunchId: Long, uploaderIds: List<Long>): Photo {
        val asset = asset("photo")
        val photo = photos.saveAndFlush(
            Photo(
                bunch = bunchRepository.findById(bunchId).orElseThrow(),
                imageAsset = asset,
                fileHash = UUID.randomUUID().toString().replace("-", "").repeat(2),
                processingStatus = ProcessingStatus.READY,
                capturedDate = null,
                dateSource = DateSource.UNKNOWN,
                createdAt = Instant.parse("2026-01-01T00:00:00Z")
            )
        )
        uploaderIds.forEach { memberId ->
            uploaders.saveAndFlush(
                PhotoUploader(
                    photo = photo,
                    member = members.findById(memberId).orElseThrow(),
                    uploadBatchId = UUID.randomUUID(),
                    uploadedAt = photo.createdAt
                )
            )
        }
        return photo
    }

    private fun draft(bunchId: Long, creator: Long, participants: List<Long>): Long {
        val id = jdbc.queryForObject(
            """INSERT INTO bundles(bunch_id,created_by,status,title,created_at,updated_at)
                VALUES (?,?,'DRAFT','초안',now(),now()) RETURNING id""",
            Long::class.java,
            bunchId,
            creator
        )!!
        participants.forEach { participant(id, it) }
        return id
    }

    private fun published(bunchId: Long, creator: Long): Long = jdbc.queryForObject(
        """INSERT INTO bundles(bunch_id,created_by,status,title,visibility,published_at,created_at,updated_at)
            VALUES (?,?,'PUBLISHED','발행','PUBLIC',now(),now(),now()) RETURNING id""",
        Long::class.java,
        bunchId,
        creator
    )!!

    private fun participant(bundleId: Long, memberId: Long) {
        jdbc.update(
            "INSERT INTO bundle_participants(bundle_id, member_id, joined_at) VALUES (?, ?, now())",
            bundleId,
            memberId
        )
    }

    private fun comment(bundleId: Long, memberId: Long, content: String) {
        jdbc.update(
            "INSERT INTO bundle_comments(bundle_id, member_id, content, created_at, updated_at) " +
                "VALUES (?, ?, ?, now(), now())",
            bundleId,
            memberId,
            content
        )
    }

    private fun participantIds(bundleId: Long): List<Long> = jdbc.queryForList(
        "SELECT member_id FROM bundle_participants WHERE bundle_id = ? ORDER BY id",
        Long::class.java,
        bundleId
    )

    private fun commentContents(bundleId: Long): List<String> = jdbc.queryForList(
        "SELECT content FROM bundle_comments WHERE bundle_id = ? ORDER BY id",
        String::class.java,
        bundleId
    )

    private fun publicBunch(owner: Long): Long = create(owner).id.also {
        jdbc.update("UPDATE bunches SET visibility = 'PUBLIC' WHERE id = ?", it)
    }

    private fun socialComment(memberId: Long, bundleId: Long, content: String, parent: Long? = null): Long =
        socialComments.create(memberId, bundleId, UUID.randomUUID(), CreateSocialCommentRequest(content, parent)).id
}
