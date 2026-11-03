package com.bunch.notification

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.domain.MembershipRole
import com.bunch.bunch.domain.Visibility
import com.bunch.bundle.domain.BundleParticipantRepository
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.domain.BundleStatus
import com.bunch.bundle.dto.BundleActor
import com.bunch.bundle.dto.BundlePhotoChangesRequest
import com.bunch.bundle.dto.BundleSnapshot
import com.bunch.bundle.dto.BundleTextRequest
import com.bunch.bundle.dto.BundleVisibilityRequest
import com.bunch.bundle.dto.CreateBundleRequest
import com.bunch.bundle.dto.PublishBundleRequest
import com.bunch.bundle.service.BundleLeaseService
import com.bunch.bundle.service.BundleService
import com.bunch.global.image.ProfileImageStorage
import com.bunch.image.domain.ImageAsset
import com.bunch.image.domain.ImageAssetRepository
import com.bunch.notification.domain.Notification
import com.bunch.notification.domain.NotificationType
import com.bunch.notification.domain.NotificationRepository
import com.bunch.notification.domain.NotificationTargetKind
import com.bunch.photo.domain.DateSource
import com.bunch.photo.domain.Photo
import com.bunch.photo.domain.PhotoRepository
import com.bunch.photo.domain.PhotoUploader
import com.bunch.photo.domain.PhotoUploaderRepository
import com.bunch.photo.domain.ProcessingStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class BundleNotificationIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var bundleService: BundleService

    @Autowired
    private lateinit var bundleLeases: BundleLeaseService

    @Autowired
    private lateinit var bunchRepository: BunchRepository

    @Autowired
    private lateinit var bundleRepository: BundleRepository

    @Autowired
    private lateinit var bundleParticipants: BundleParticipantRepository

    @Autowired
    private lateinit var assets: ImageAssetRepository

    @Autowired
    private lateinit var photos: PhotoRepository

    @Autowired
    private lateinit var uploaders: PhotoUploaderRepository

    @Autowired
    private lateinit var notifications: NotificationRepository

    @MockitoBean
    private lateinit var clock: Clock

    @MockitoBean
    private lateinit var storage: ProfileImageStorage

    private val ownedBunchIds = mutableListOf<Long>()
    private val ownedAssetIds = mutableListOf<Long>()
    private var rejectionTriggerName: String? = null
    private var rejectionFunctionName: String? = null

    @BeforeEach
    fun setDefaultClock() {
        `when`(clock.instant()).thenReturn(DEFAULT_CLOCK_INSTANT)
    }

    @AfterEach
    fun cleanBundleReferencesBeforeBunchSupport() {
        allowNotificationWrites()
        jdbc.update("DELETE FROM bundle_edit_leases")
        jdbc.update("DELETE FROM bundle_comments")
        jdbc.update("DELETE FROM bundle_participants")
        jdbc.update("DELETE FROM bundle_photos")
        jdbc.update("DELETE FROM bundle_requests")
        jdbc.update("DELETE FROM bundles")
        jdbc.update("DELETE FROM photo_uploaders")
        jdbc.update("DELETE FROM photos")
        ownedAssetIds.forEach { jdbc.update("DELETE FROM image_assets WHERE id = ?", it) }
        ownedBunchIds.clear()
        ownedAssetIds.clear()
    }

    @Test
    fun `creation notifies photo upload participants and create replay adds no notification`() {
        val owner = member()
        val firstUploader = member()
        val secondUploader = member()
        val outsider = member()
        val bunchId = bunchWithMembers(owner, firstUploader, secondUploader)
        val firstPhoto = photo(bunchId, listOf(firstUploader))
        val secondPhoto = photo(bunchId, listOf(secondUploader, outsider))
        val key = UUID.randomUUID()
        val request = CreateBundleRequest(listOf(firstPhoto.id, secondPhoto.id))

        val created = bundleService.create(actor(owner), bunchId, key, request)

        assertThat(created.participants.map { it.memberId })
            .containsExactlyInAnyOrder(owner, firstUploader, secondUploader)
        val firstNotifications = ofType(bunchId, NotificationType.DRAFT_PARTICIPANT_ADDED)
        assertThat(firstNotifications.map { it.recipientMemberId })
            .containsExactlyInAnyOrder(firstUploader, secondUploader)
        assertThat(firstNotifications.map { it.recipientMemberId }).doesNotContain(owner, outsider)

        val replay = bundleService.create(actor(owner), bunchId, key, request)

        assertThat(replay.id).isEqualTo(created.id)
        assertThat(replay.participants).hasSize(3)
        assertThat(ofType(bunchId, NotificationType.DRAFT_PARTICIPANT_ADDED))
            .extracting<Long> { it.id }
            .containsExactlyElementsOf(firstNotifications.map { it.id })
    }

    @Test
    fun `bundle creation and late participation use the service clock for joined timestamps`() {
        val creationClock = Instant.parse("2026-01-01T00:00:00Z")
        val laterClock = Instant.parse("2026-01-02T00:00:00Z")
        `when`(clock.instant()).thenReturn(creationClock)

        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val draft = createDraft(owner, bunchId, photo(bunchId, listOf(owner)))
        val initialParticipant = bundleParticipants.findByBundleIdAndMemberId(draft.id, owner)

        assertThat(bundleRepository.findById(draft.id).orElseThrow().createdAt).isEqualTo(creationClock)
        assertThat(initialParticipant!!.joinedAt).isEqualTo(creationClock)

        val lateParticipant = member()
        join(lateParticipant, issue(owner, bunchId))
        `when`(clock.instant()).thenReturn(laterClock)

        bundleService.addParticipant(
            actor(owner),
            bunchId,
            draft.id,
            lateParticipant,
            "late-participant"
        )

        assertThat(bundleParticipants.findByBundleIdAndMemberId(draft.id, lateParticipant)!!.joinedAt)
            .isEqualTo(laterClock)
    }

    @Test
    fun `participant add remove and leave notifications honor no-ops targets and actor exclusion`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val draft = createDraft(owner, bunchId, photo(bunchId, listOf(owner)))

        bundleService.addParticipant(actor(owner), bunchId, draft.id, guest, "guest-add")
        bundleService.addParticipant(actor(owner), bunchId, draft.id, guest, "guest-add-replay")
        assertThat(ofType(bunchId, NotificationType.DRAFT_PARTICIPANT_ADDED))
            .extracting<Long> { it.recipientMemberId }
            .containsExactly(guest)

        bundleService.removeParticipant(actor(owner), bunchId, draft.id, guest, "guest-remove")
        val removed = ofType(bunchId, NotificationType.DRAFT_PARTICIPANT_REMOVED)
        assertThat(removed).hasSize(1)
        assertThat(removed.single().recipientMemberId).isEqualTo(guest)
        assertThat(removed.single().target.kind).isEqualTo(NotificationTargetKind.DRAFT_LIST)
        assertThat(removed.single().target.bundleId).isNull()
        assertThat(ofType(bunchId, NotificationType.DRAFT_PARTICIPANTS_INSUFFICIENT)).isEmpty()

        bundleService.removeParticipant(actor(owner), bunchId, draft.id, guest, "guest-remove-no-op")
        assertThat(ofType(bunchId, NotificationType.DRAFT_PARTICIPANT_REMOVED)).hasSize(1)

        bundleService.addParticipant(actor(owner), bunchId, draft.id, guest, "guest-readd")
        bundleService.leave(actor(guest), bunchId, draft.id, "guest-leave")

        assertThat(ofType(bunchId, NotificationType.DRAFT_PARTICIPANT_REMOVED)).hasSize(1)
        val insufficient = ofType(bunchId, NotificationType.DRAFT_PARTICIPANTS_INSUFFICIENT)
        assertThat(insufficient).hasSize(1)
        assertThat(insufficient.single().recipientMemberId).isEqualTo(owner)
    }

    @Test
    fun `draft photo activity is produced by BundleService, groups within ten minutes and stops after publish`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val source = photo(bunchId, listOf(owner))
        val firstAddition = photo(bunchId, listOf(owner))
        val secondAddition = photo(bunchId, listOf(owner))
        val expiredAddition = photo(bunchId, listOf(owner))
        val publishedAddition = photo(bunchId, listOf(owner))
        val draft = createDraft(owner, bunchId, source)
        bundleService.addParticipant(actor(owner), bunchId, draft.id, guest, "activity-guest-add")

        val firstKey = UUID.randomUUID()
        val firstRequest = BundlePhotoChangesRequest(
            addPhotoIds = listOf(firstAddition.id),
            mode = BundleStatus.DRAFT
        )
        val afterFirst = bundleService.changePhotos(actor(owner), bunchId, draft.id, firstKey, firstRequest)
        val firstActivity = ofType(bunchId, NotificationType.DRAFT_ACTIVITY).single()
        assertThat(firstActivity.recipientMemberId).isEqualTo(guest)
        assertThat(firstActivity.activityData!!.photoCount).isEqualTo(1)
        assertThat(ofType(bunchId, NotificationType.DRAFT_ACTIVITY).map { it.recipientMemberId })
            .doesNotContain(owner)

        val replay = bundleService.changePhotos(actor(owner), bunchId, draft.id, firstKey, firstRequest)
        assertThat(replay.revision).isEqualTo(afterFirst.revision)
        assertThat(bundleService.get(owner, bunchId, draft.id).photos.map { it.photoId })
            .containsExactly(source.id, firstAddition.id)
        assertThat(ofType(bunchId, NotificationType.DRAFT_ACTIVITY)).hasSize(1)
        assertThat(ofType(bunchId, NotificationType.DRAFT_ACTIVITY).single().activityData!!.photoCount)
            .isEqualTo(1)

        bundleService.changePhotos(
            actor(owner),
            bunchId,
            draft.id,
            UUID.randomUUID(),
            BundlePhotoChangesRequest(
                addPhotoIds = listOf(secondAddition.id),
                mode = BundleStatus.DRAFT
            )
        )
        val merged = ofType(bunchId, NotificationType.DRAFT_ACTIVITY).single()
        assertThat(merged.id).isEqualTo(firstActivity.id)
        assertThat(merged.activityData!!.photoCount).isEqualTo(2)

        jdbc.update(
            """UPDATE notifications
               SET group_until = TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'
               WHERE bunch_id = ? AND type = 'DRAFT_ACTIVITY'
               AND target ->> 'bundleId' = CAST(? AS text)""",
            bunchId,
            draft.id
        )
        bundleService.changePhotos(
            actor(owner),
            bunchId,
            draft.id,
            UUID.randomUUID(),
            BundlePhotoChangesRequest(
                addPhotoIds = listOf(expiredAddition.id),
                mode = BundleStatus.DRAFT
            )
        )
        val afterWindow = ofType(bunchId, NotificationType.DRAFT_ACTIVITY)
        assertThat(afterWindow).hasSize(2)
        assertThat(afterWindow.first().activityData!!.photoCount).isEqualTo(2)
        assertThat(afterWindow.last().activityData!!.photoCount).isEqualTo(1)

        val ready = makeReady(owner, guest, bunchId, draft.id, "activity")
        val published = bundleService.publish(
            actor(owner),
            bunchId,
            draft.id,
            UUID.randomUUID(),
            PublishBundleRequest(ready.revision)
        )
        val beforePublishedEdits = notificationFingerprint(notificationsFor(bunchId))
        val titled = bundleService.title(
            actor(owner),
            bunchId,
            published.id,
            BundleTextRequest("published edit", published.titleVersion, BundleStatus.PUBLISHED),
            "published-title"
        )
        val visible = bundleService.visibility(
            actor(owner),
            bunchId,
            published.id,
            BundleVisibilityRequest(Visibility.PUBLIC, titled.visibilityVersion, BundleStatus.PUBLISHED),
            "published-visibility"
        )
        val ownerComment = visible.comments.first { it.memberId == owner }
        val commented = bundleService.comment(
            actor(owner),
            bunchId,
            published.id,
            BundleTextRequest("published comment", ownerComment.version, BundleStatus.PUBLISHED),
            "published-comment"
        )
        bundleService.changePhotos(
            actor(owner),
            bunchId,
            published.id,
            UUID.randomUUID(),
            BundlePhotoChangesRequest(
                addPhotoIds = listOf(publishedAddition.id),
                mode = BundleStatus.PUBLISHED
            )
        )

        assertThat(commented.status).isEqualTo(BundleStatus.PUBLISHED)
        assertThat(notificationFingerprint(notificationsFor(bunchId)))
            .containsExactlyElementsOf(beforePublishedEdits)
    }

    @Test
    fun `ready notification selects participating managers once and incomplete removal stays one-shot`() {
        val owner = member()
        val coOwner = member()
        val participant = member()
        val nonParticipatingAdmin = member()
        val bunchId = bunchWithMembers(owner, coOwner, participant, nonParticipatingAdmin)
        setRole(bunchId, coOwner, MembershipRole.CO_OWNER)
        setRole(bunchId, nonParticipatingAdmin, MembershipRole.CO_OWNER)

        val firstDraft = createDraft(owner, bunchId, photo(bunchId, listOf(owner)))
        bundleService.addParticipant(actor(owner), bunchId, firstDraft.id, coOwner, "ready-manager-add")
        bundleService.addParticipant(actor(owner), bunchId, firstDraft.id, participant, "ready-member-add")
        makeReady(owner, coOwner, bunchId, firstDraft.id, "first-ready")
        writeComment(participant, bunchId, firstDraft.id, "last comment", "first-ready-final")
        assertThat(bundleService.get(owner, bunchId, firstDraft.id).publishBlockers).isEmpty()

        val firstReady = readyFor(bunchId, firstDraft.id)
        assertThat(firstReady.map { it.recipientMemberId })
            .containsExactlyInAnyOrder(owner, coOwner)
        assertThat(firstReady.map { it.recipientMemberId })
            .doesNotContain(participant, nonParticipatingAdmin)

        bundleService.addParticipant(
            actor(owner),
            bunchId,
            firstDraft.id,
            nonParticipatingAdmin,
            "ready-break"
        )
        bundleService.removeParticipant(
            actor(coOwner),
            bunchId,
            firstDraft.id,
            nonParticipatingAdmin,
            "ready-recomplete"
        )
        assertThat(readyFor(bunchId, firstDraft.id).map { it.id })
            .containsExactlyElementsOf(firstReady.map { it.id })

        val secondDraft = createDraft(owner, bunchId, photo(bunchId, listOf(owner)))
        bundleService.addParticipant(actor(owner), bunchId, secondDraft.id, coOwner, "remove-ready-manager")
        bundleService.addParticipant(actor(owner), bunchId, secondDraft.id, participant, "remove-ready-incomplete")
        makeReady(owner, coOwner, bunchId, secondDraft.id, "remove-ready")
        bundleService.removeParticipant(
            actor(coOwner),
            bunchId,
            secondDraft.id,
            participant,
            "remove-ready-trigger"
        )

        val removalReady = readyFor(bunchId, secondDraft.id)
        assertThat(removalReady).hasSize(1)
        assertThat(removalReady.single().recipientMemberId).isEqualTo(owner)
        bundleService.addParticipant(actor(owner), bunchId, secondDraft.id, participant, "remove-ready-break")
        bundleService.removeParticipant(
            actor(coOwner),
            bunchId,
            secondDraft.id,
            participant,
            "remove-ready-recomplete"
        )
        assertThat(readyFor(bunchId, secondDraft.id).map { it.id })
            .containsExactlyElementsOf(removalReady.map { it.id })
    }

    @Test
    fun `publication notifies all active members, deduplicates retries and re-alerts after stage falls`() {
        val owner = member()
        val guest = member()
        val otherActiveMember = member()
        val bunchId = bunchWithMembers(owner, guest, otherActiveMember)

        val firstDraft = publishableDraft(owner, guest, bunchId, photo(bunchId, listOf(owner)), "stage-one")
        val firstPublished = publish(owner, bunchId, firstDraft, UUID.randomUUID())
        assertThat(publishedFor(bunchId, firstPublished.id).map { it.recipientMemberId })
            .containsExactlyInAnyOrder(guest, otherActiveMember)
        assertThat(publishedFor(bunchId, firstPublished.id).map { it.recipientMemberId })
            .doesNotContain(owner)
        assertThat(growthFor(bunchId, firstPublished.id)).isEmpty()

        val secondDraft = publishableDraft(owner, guest, bunchId, photo(bunchId, listOf(owner)), "stage-two")
        val secondKey = UUID.randomUUID()
        val secondPublished = publish(owner, bunchId, secondDraft, secondKey)
        assertThat(growthFor(bunchId, secondPublished.id).map { it.recipientMemberId })
            .containsExactlyInAnyOrder(guest, otherActiveMember)
        val beforeReplay = notificationFingerprint(notificationsFor(bunchId))
        bundleService.publish(
            actor(owner),
            bunchId,
            secondPublished.id,
            secondKey,
            PublishBundleRequest(secondDraft.revision)
        )
        bundleService.publish(
            actor(owner),
            bunchId,
            secondPublished.id,
            UUID.randomUUID(),
            PublishBundleRequest(secondDraft.revision)
        )
        assertThat(notificationFingerprint(notificationsFor(bunchId)))
            .containsExactlyElementsOf(beforeReplay)

        bundleService.delete(actor(owner), bunchId, secondPublished.id, "stage-falls")
        assertThat(bundleService.summary(owner, bunchId).stage).isEqualTo(1)

        val thirdDraft = publishableDraft(owner, guest, bunchId, photo(bunchId, listOf(owner)), "stage-rises-again")
        val thirdPublished = publish(owner, bunchId, thirdDraft, UUID.randomUUID())
        assertThat(growthFor(bunchId, thirdPublished.id).map { it.recipientMemberId })
            .containsExactlyInAnyOrder(guest, otherActiveMember)
    }

    @Test
    fun `notification insert failure rolls back creation and photo mutation and permits the same key retry`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val source = photo(bunchId, listOf(guest))
        val createKey = UUID.randomUUID()
        val createRequest = CreateBundleRequest(listOf(source.id))

        rejectNotificationWrites()
        assertThatThrownBy {
            bundleService.create(actor(owner), bunchId, createKey, createRequest)
        }.isNotNull
        assertThat(bundleRepository.countByBunchIdAndStatus(bunchId, BundleStatus.DRAFT)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bundle_photos", Long::class.java)).isZero()
        assertThat(bundleParticipants.count()).isZero()
        assertThat(notificationsFor(bunchId)).isEmpty()

        allowNotificationWrites()
        val created = bundleService.create(actor(owner), bunchId, createKey, createRequest)
        assertThat(ofType(bunchId, NotificationType.DRAFT_PARTICIPANT_ADDED))
            .extracting<Long> { it.recipientMemberId }
            .containsExactly(guest)

        val addition = photo(bunchId, listOf(owner))
        val photoKey = UUID.randomUUID()
        val photoRequest = BundlePhotoChangesRequest(
            addPhotoIds = listOf(addition.id),
            mode = BundleStatus.DRAFT
        )
        val beforePhotoIds = created.photos.map { it.photoId }
        val beforeFailure = notificationFingerprint(notificationsFor(bunchId))
        rejectNotificationWrites()
        assertThatThrownBy {
            bundleService.changePhotos(actor(owner), bunchId, created.id, photoKey, photoRequest)
        }.isNotNull
        assertThat(bundleService.get(owner, bunchId, created.id).photos.map { it.photoId })
            .containsExactlyElementsOf(beforePhotoIds)
        assertThat(notificationFingerprint(notificationsFor(bunchId)))
            .containsExactlyElementsOf(beforeFailure)

        allowNotificationWrites()
        val retried = bundleService.changePhotos(actor(owner), bunchId, created.id, photoKey, photoRequest)
        assertThat(retried.photos.map { it.photoId }).containsExactly(source.id, addition.id)
        assertThat(ofType(bunchId, NotificationType.DRAFT_ACTIVITY)).hasSize(1)
    }

    @Test
    fun `notification insert failure rolls back readiness and publication and permits both retries`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val draft = createDraft(owner, bunchId, photo(bunchId, listOf(owner)))
        bundleService.addParticipant(actor(owner), bunchId, draft.id, guest, "rollback-guest-add")
        writeTitle(owner, bunchId, draft.id, "rollback", "rollback-title")
        bundleService.visibility(
            actor(owner),
            bunchId,
            draft.id,
            BundleVisibilityRequest(Visibility.PRIVATE, 0, BundleStatus.DRAFT),
            "rollback-visibility"
        )
        writeComment(owner, bunchId, draft.id, "owner comment", "rollback-owner-comment")

        val guestCommentVersion = bundleService.get(owner, bunchId, draft.id)
            .comments.first { it.memberId == guest }.version
        val guestCommentRequest = BundleTextRequest(
            "guest comment",
            guestCommentVersion,
            BundleStatus.DRAFT
        )
        val beforeCommentFailure = notificationFingerprint(notificationsFor(bunchId))
        rejectNotificationWrites()
        assertThatThrownBy {
            bundleService.comment(
                actor(guest),
                bunchId,
                draft.id,
                guestCommentRequest,
                "rollback-guest-comment"
            )
        }.isNotNull
        assertThat(bundleService.get(owner, bunchId, draft.id).comments.first { it.memberId == guest }.content)
            .isEmpty()
        assertThat(notificationFingerprint(notificationsFor(bunchId)))
            .containsExactlyElementsOf(beforeCommentFailure)

        allowNotificationWrites()
        val ready = bundleService.comment(
            actor(guest),
            bunchId,
            draft.id,
            guestCommentRequest,
            "rollback-guest-comment"
        )
        assertThat(readyFor(bunchId, draft.id)).hasSize(1)

        val publishKey = UUID.randomUUID()
        val beforePublishFailure = notificationFingerprint(notificationsFor(bunchId))
        rejectNotificationWrites()
        assertThatThrownBy {
            bundleService.publish(
                actor(owner),
                bunchId,
                draft.id,
                publishKey,
                PublishBundleRequest(ready.revision)
            )
        }.isNotNull
        val afterPublishFailure = bundleService.get(owner, bunchId, draft.id)
        assertThat(afterPublishFailure.status).isEqualTo(BundleStatus.DRAFT)
        assertThat(afterPublishFailure.revision).isEqualTo(ready.revision)
        assertThat(notificationFingerprint(notificationsFor(bunchId)))
            .containsExactlyElementsOf(beforePublishFailure)

        allowNotificationWrites()
        val published = bundleService.publish(
            actor(owner),
            bunchId,
            draft.id,
            publishKey,
            PublishBundleRequest(ready.revision)
        )
        assertThat(published.status).isEqualTo(BundleStatus.PUBLISHED)
        assertThat(publishedFor(bunchId, draft.id).map { it.recipientMemberId })
            .containsExactly(guest)
    }

    private fun createDraft(owner: Long, bunchId: Long, source: Photo): BundleSnapshot =
        bundleService.create(
            actor(owner),
            bunchId,
            UUID.randomUUID(),
            CreateBundleRequest(listOf(source.id))
        )

    private fun makeReady(
        owner: Long,
        participant: Long,
        bunchId: Long,
        bundleId: Long,
        label: String
    ): BundleSnapshot {
        writeTitle(owner, bunchId, bundleId, "$label title", "$label-title")
        val titled = bundleService.get(owner, bunchId, bundleId)
        bundleService.visibility(
            actor(owner),
            bunchId,
            bundleId,
            BundleVisibilityRequest(Visibility.PRIVATE, titled.visibilityVersion, BundleStatus.DRAFT),
            "$label-visibility"
        )
        writeComment(owner, bunchId, bundleId, "$label owner comment", "$label-owner-comment")
        writeComment(participant, bunchId, bundleId, "$label participant comment", "$label-participant-comment")
        return bundleService.get(owner, bunchId, bundleId)
    }

    private fun publishableDraft(
        owner: Long,
        participant: Long,
        bunchId: Long,
        source: Photo,
        label: String
    ): BundleSnapshot {
        val draft = createDraft(owner, bunchId, source)
        bundleService.addParticipant(actor(owner), bunchId, draft.id, participant, "$label-add")
        return makeReady(owner, participant, bunchId, draft.id, label)
    }

    private fun publish(owner: Long, bunchId: Long, draft: BundleSnapshot, key: UUID): BundleSnapshot =
        bundleService.publish(
            actor(owner),
            bunchId,
            draft.id,
            key,
            PublishBundleRequest(draft.revision)
        )

    private fun writeTitle(owner: Long, bunchId: Long, bundleId: Long, value: String, label: String) {
        val current = bundleService.get(owner, bunchId, bundleId)
        val lease = bundleLeases.acquire(
            actor(owner),
            bunchId,
            bundleId,
            "TITLE",
            "$label-${UUID.randomUUID()}"
        )
        bundleService.title(
            actor(owner),
            bunchId,
            bundleId,
            BundleTextRequest(value, current.titleVersion, BundleStatus.DRAFT, lease.token),
            label
        )
    }

    private fun writeComment(
        memberId: Long,
        bunchId: Long,
        bundleId: Long,
        value: String,
        requestId: String
    ) {
        val current = bundleService.get(memberId, bunchId, bundleId)
        val version = current.comments.firstOrNull { it.memberId == memberId }?.version ?: 0
        bundleService.comment(
            actor(memberId),
            bunchId,
            bundleId,
            BundleTextRequest(value, version, BundleStatus.DRAFT),
            requestId
        )
    }

    private fun actor(memberId: Long, sessionId: String = "bundle-notification-$memberId") =
        BundleActor(memberId, sessionId)

    private fun bunchWithMembers(vararg memberIds: Long): Long {
        require(memberIds.size >= 2)
        val owner = memberIds.first()
        val bunchId = ownedBunch(create(owner).id)
        memberIds.drop(1).forEach { join(it, issue(owner, bunchId)) }
        return bunchId
    }

    private fun setRole(bunchId: Long, memberId: Long, role: MembershipRole) {
        jdbc.update(
            "UPDATE bunch_memberships SET role = ? WHERE bunch_id = ? AND member_id = ? AND status = 'ACTIVE'",
            role.name,
            bunchId,
            memberId
        )
    }

    private fun ownedBunch(id: Long): Long {
        ownedBunchIds += id
        return id
    }

    private fun photo(
        bunchId: Long,
        uploadedBy: List<Long>,
        capturedDate: LocalDate? = LocalDate.of(2024, 1, 1),
        capturedTime: LocalTime? = if (capturedDate == null) null else LocalTime.NOON,
        status: ProcessingStatus = ProcessingStatus.READY
    ): Photo {
        val asset = assets.saveAndFlush(
            ImageAsset(
                publicId = "bundle-notification-test/${UUID.randomUUID()}",
                url = "https://images.example/bundle-notification/${UUID.randomUUID()}.jpg"
            )
        )
        ownedAssetIds += asset.id
        val saved = photos.saveAndFlush(
            Photo(
                bunch = bunchRepository.findById(bunchId).orElseThrow(),
                imageAsset = asset,
                fileHash = UUID.randomUUID().toString().replace("-", "").repeat(2),
                processingStatus = status,
                capturedDate = capturedDate,
                capturedTime = capturedTime,
                dateSource = if (capturedDate == null) DateSource.UNKNOWN else DateSource.USER,
                createdAt = Instant.now()
            )
        )
        uploadedBy.forEach { memberId ->
            uploaders.saveAndFlush(
                PhotoUploader(
                    photo = saved,
                    member = members.findById(memberId).orElseThrow(),
                    uploadBatchId = UUID.randomUUID()
                )
            )
        }
        return saved
    }

    private fun notificationsFor(bunchId: Long): List<Notification> = notifications.findAll()
        .filter { it.bunchId == bunchId && it.type in bundleNotificationTypes }
        .sortedBy { it.id }

    private fun ofType(bunchId: Long, type: NotificationType): List<Notification> =
        notificationsFor(bunchId).filter { it.type == type }

    private fun readyFor(bunchId: Long, bundleId: Long): List<Notification> =
        ofType(bunchId, NotificationType.DRAFT_READY_TO_PUBLISH)
            .filter { it.target.bundleId == bundleId }

    private fun publishedFor(bunchId: Long, bundleId: Long): List<Notification> =
        ofType(bunchId, NotificationType.BUNDLE_PUBLISHED)
            .filter { it.target.bundleId == bundleId }

    private fun growthFor(bunchId: Long, bundleId: Long): List<Notification> =
        ofType(bunchId, NotificationType.CHARACTER_GROWN)
            .filter { it.target.bunchId == bunchId && it.dedupeKey.contains(":$bundleId:") }

    private fun notificationFingerprint(rows: List<Notification>): List<Triple<NotificationType, Long, String>> =
        rows.map { Triple(it.type, it.recipientMemberId, it.dedupeKey) }

    private fun rejectNotificationWrites() {
        check(rejectionTriggerName == null && rejectionFunctionName == null)
        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        val triggerName = "bundle_notification_trigger_$suffix"
        val functionName = "bundle_notification_reject_$suffix"
        rejectionTriggerName = triggerName
        rejectionFunctionName = functionName
        jdbc.execute(
            """CREATE FUNCTION ${functionName}() RETURNS trigger LANGUAGE plpgsql AS
               'BEGIN RAISE EXCEPTION ''injected bundle notification write failure''; END'"""
        )
        jdbc.execute(
            "CREATE TRIGGER $triggerName BEFORE INSERT ON notifications " +
                "FOR EACH ROW EXECUTE FUNCTION $functionName()"
        )
    }

    private fun allowNotificationWrites() {
        rejectionTriggerName?.let { jdbc.execute("DROP TRIGGER IF EXISTS $it ON notifications") }
        rejectionFunctionName?.let { jdbc.execute("DROP FUNCTION IF EXISTS $it()") }
        rejectionTriggerName = null
        rejectionFunctionName = null
    }

    private companion object {
        val DEFAULT_CLOCK_INSTANT: Instant = Instant.parse("2026-01-01T00:00:00Z")

        val bundleNotificationTypes = setOf(
            NotificationType.DRAFT_PARTICIPANT_ADDED,
            NotificationType.DRAFT_ACTIVITY,
            NotificationType.DRAFT_PARTICIPANT_REMOVED,
            NotificationType.DRAFT_READY_TO_PUBLISH,
            NotificationType.DRAFT_PARTICIPANTS_INSUFFICIENT,
            NotificationType.BUNDLE_PUBLISHED,
            NotificationType.CHARACTER_GROWN
        )
    }
}
