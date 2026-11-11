package com.bunch.bundle

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bunch.domain.MembershipRole
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.domain.Visibility
import com.bunch.bundle.domain.BundlePhotoRepository
import com.bunch.bundle.domain.BundleParticipantRepository
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.domain.BundleStatus
import com.bunch.bundle.dto.EditPublishedBundleRequest
import com.bunch.bundle.dto.PublishedBundlePhotoInput
import com.bunch.bundle.dto.BundleActor
import com.bunch.bundle.dto.BundleSnapshot
import com.bunch.bundle.dto.BundleTextRequest
import com.bunch.bundle.dto.BundleDateRequest
import com.bunch.bundle.dto.BundlePhotoChangesRequest
import com.bunch.bundle.dto.BundleVisibilityRequest
import com.bunch.bundle.dto.CreateBundleRequest
import com.bunch.bundle.dto.MoveBundlePhotoRequest
import com.bunch.bundle.dto.PublishBundleRequest
import com.bunch.bundle.service.BundleLeaseService
import com.bunch.bundle.service.BundleService
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.image.ProfileImageStorage
import com.bunch.image.domain.ImageAsset
import com.bunch.image.domain.ImageAssetRepository
import com.bunch.photo.domain.DateSource
import com.bunch.photo.domain.Photo
import com.bunch.photo.domain.PhotoRepository
import com.bunch.photo.domain.PhotoUploader
import com.bunch.photo.domain.PhotoUploaderRepository
import com.bunch.photo.domain.ProcessingStatus
import com.bunch.photo.service.PhotoUploaderRemovalResult
import com.bunch.photo.service.PhotoService
import com.bunch.bundle.controller.BundleSocketController
import com.bunch.bundle.dto.BundleLeaseCommand
import com.bunch.bundle.service.BundleRealtimeSessionRegistry
import com.bunch.global.security.JwtSessionDetails
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class BundleLifecycleIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var bundleService: BundleService

    @Autowired
    private lateinit var bundleLeases: BundleLeaseService

    @Autowired
    private lateinit var bunchRepository: BunchRepository

    @Autowired
    private lateinit var bundleRepository: BundleRepository

    @Autowired
    private lateinit var bundlePhotos: BundlePhotoRepository

    @Autowired
    private lateinit var bundleParticipants: BundleParticipantRepository

    @Autowired
    private lateinit var assets: ImageAssetRepository

    @Autowired
    private lateinit var photos: PhotoRepository

    @Autowired
    private lateinit var uploaders: PhotoUploaderRepository

    @Autowired
    private lateinit var photoService: PhotoService

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @MockitoBean
    private lateinit var storage: ProfileImageStorage

    @Autowired
    private lateinit var socketController: BundleSocketController

    @Autowired
    private lateinit var realtimeRegistry: BundleRealtimeSessionRegistry

    private val ownedBunchIds = mutableListOf<Long>()
    private val ownedAssetIds = mutableListOf<Long>()

    @AfterEach
    fun cleanBundleReferencesBeforeBunchSupport() {
        // BunchIntegrationSupport deletes bunches and members in its superclass hook.
        // Clear the bundle graph first so RESTRICT member/image FKs never depend on hook order.
        jdbc.execute("DROP TRIGGER IF EXISTS bundle_test_reject_participant ON bundle_participants")
        jdbc.execute("DROP TRIGGER IF EXISTS bundle_test_reject_photo ON bundle_photos")
        jdbc.execute("DROP FUNCTION IF EXISTS bunch_test_reject_write()")
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
    fun `creation requires two active members`() {
        val owner = member()
        val bunchId = ownedBunch(create(owner).id)
        val photo = photo(bunchId, uploadedBy = listOf(owner))

        assertBundleError(ErrorCode.BUNDLE_LIMIT_EXCEEDED) {
            bundleService.create(actor(owner), bunchId, UUID.randomUUID(), CreateBundleRequest(listOf(photo.id)))
        }

        val guest = member()
        join(guest, issue(owner, bunchId))
        val created = bundleService.create(
            actor(owner),
            bunchId,
            UUID.randomUUID(),
            CreateBundleRequest(listOf(photo.id))
        )

        assertThat(created.status).isEqualTo(BundleStatus.DRAFT)
        assertThat(created.photos.map { it.photoId }).containsExactly(photo.id)
    }

    @Test
    fun `creation supports one through twenty photos in requested order and rejects invalid selections`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val sourcePhotos = (1..21).map { photo(bunchId, uploadedBy = listOf(owner)) }

        val one = createDraft(owner, bunchId, listOf(sourcePhotos.first().id))
        assertThat(one.photos).hasSize(1)

        val selectedTwenty = sourcePhotos.take(20).map { it.id }.reversed()
        val twenty = createDraft(owner, bunchId, selectedTwenty)
        assertThat(twenty.photos.map { it.photoId }).containsExactlyElementsOf(selectedTwenty)
        assertThat(twenty.photos.map { it.position }).containsExactlyElementsOf((0 until 20).toList())

        assertBundleError(ErrorCode.INVALID_INPUT) {
            bundleService.create(actor(owner), bunchId, UUID.randomUUID(), CreateBundleRequest(emptyList()))
        }
        assertBundleError(ErrorCode.INVALID_INPUT) {
            bundleService.create(
                actor(owner),
                bunchId,
                UUID.randomUUID(),
                CreateBundleRequest(sourcePhotos.map { it.id })
            )
        }
        assertBundleError(ErrorCode.INVALID_INPUT) {
            bundleService.create(
                actor(owner),
                bunchId,
                UUID.randomUUID(),
                CreateBundleRequest(listOf(sourcePhotos.first().id, sourcePhotos.first().id))
            )
        }
    }

    @Test
    fun `creation enforces ten draft limit and rejects a photo from another bunch`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)

        val otherOwner = member()
        val otherGuest = member()
        val otherBunchId = bunchWithMembers(otherOwner, otherGuest)
        val foreignPhoto = photo(otherBunchId, uploadedBy = listOf(otherOwner))
        assertBundleError(ErrorCode.INVALID_INPUT) {
            bundleService.create(
                actor(owner),
                bunchId,
                UUID.randomUUID(),
                CreateBundleRequest(listOf(foreignPhoto.id))
            )
        }

        val photo = photo(bunchId, uploadedBy = listOf(owner))
        repeat(10) { createDraft(owner, bunchId, listOf(photo.id)) }
        assertThat(bundleRepository.countByBunchIdAndStatus(bunchId, BundleStatus.DRAFT)).isEqualTo(10)
        assertBundleError(ErrorCode.BUNDLE_LIMIT_EXCEEDED) {
            createDraft(owner, bunchId, listOf(photo.id))
        }

        val limitedOwner = member()
        val limitedGuest = member()
        val limitedBunchId = bunchWithMembers(limitedOwner, limitedGuest)
        val limitedPhoto = photo(limitedBunchId, uploadedBy = listOf(limitedOwner))
        repeat(10) { createDraft(limitedOwner, limitedBunchId, listOf(limitedPhoto.id)) }
        assertThat(bundleRepository.countByBunchIdAndStatus(limitedBunchId, BundleStatus.DRAFT)).isEqualTo(10)
        assertBundleError(ErrorCode.BUNDLE_LIMIT_EXCEEDED) {
            createDraft(limitedOwner, limitedBunchId, listOf(limitedPhoto.id))
        }
    }

    @Test
    fun `initial participants are the creator plus the current bunch uploader union`() {
        val owner = member()
        val first = member()
        val second = member()
        val outsider = member()
        val bunchId = bunchWithMembers(owner, first, second)
        val firstPhoto = photo(bunchId, uploadedBy = listOf(first))
        val secondPhoto = photo(bunchId, uploadedBy = listOf(second, outsider))

        val created = createDraft(owner, bunchId, listOf(firstPhoto.id, secondPhoto.id))

        assertThat(created.participants.map { it.memberId })
            .containsExactly(owner, first, second)
        assertThat(created.permissions.creator).isTrue()
        assertThat(created.permissions.participant).isTrue()
    }

    @Test
    fun `participation permissions and removal delete only the removed member comment`() {
        val owner = member()
        val participant = member()
        val candidate = member()
        val bunchId = bunchWithMembers(owner, participant, candidate)
        val source = photo(bunchId, uploadedBy = listOf(owner))
        val draft = createDraft(owner, bunchId, listOf(source.id))
        bundleService.addParticipant(
            actor(owner),
            bunchId,
            draft.id,
            participant,
            "add-participant"
        )
        val participantCommentVersion = bundleService.get(owner, bunchId, draft.id)
            .comments.firstOrNull { it.memberId == participant }?.version ?: 0

        val participantComment = bundleService.comment(
            actor(participant),
            bunchId,
            draft.id,
            BundleTextRequest("participant comment", participantCommentVersion, BundleStatus.DRAFT),
            "participant-comment"
        )
        assertThat(participantComment.comments.single().memberId).isEqualTo(participant)

        assertBundleError(ErrorCode.FORBIDDEN) {
            bundleService.addParticipant(actor(candidate), bunchId, draft.id, candidate, "candidate-add")
        }
        assertBundleError(ErrorCode.FORBIDDEN) {
            bundleService.removeParticipant(actor(participant), bunchId, draft.id, candidate, "member-remove")
        }
        assertBundleError(ErrorCode.FORBIDDEN) {
            bundleService.comment(
                actor(candidate),
                bunchId,
                draft.id,
                BundleTextRequest("not a participant", 0, BundleStatus.DRAFT),
                "candidate-comment"
            )
        }

        val removed = bundleService.removeParticipant(
            actor(owner),
            bunchId,
            draft.id,
            participant,
            "owner-remove"
        )
        assertThat(removed.participants.map { it.memberId }).containsExactly(owner)
        assertThat(removed.comments).isEmpty()
        assertBundleError(ErrorCode.FORBIDDEN) {
            bundleService.comment(
                actor(participant),
                bunchId,
                draft.id,
                BundleTextRequest("late comment", 0, BundleStatus.DRAFT),
                "late-comment"
            )
        }

        bundleService.addParticipant(actor(owner), bunchId, draft.id, participant, "re-add")
        val readdedCommentVersion = bundleService.get(owner, bunchId, draft.id)
            .comments.firstOrNull { it.memberId == participant }?.version ?: 0
        bundleService.comment(
            actor(participant),
            bunchId,
            draft.id,
            BundleTextRequest("removed by leave", readdedCommentVersion, BundleStatus.DRAFT),
            "leave-comment"
        )
        val afterLeave = bundleService.leave(actor(participant), bunchId, draft.id, "participant-leave")
        assertThat(afterLeave!!.participants.map { it.memberId }).containsExactly(owner)
        assertThat(afterLeave.comments).isEmpty()

        assertThat(bundleService.leave(actor(owner), bunchId, draft.id, "creator-leave")).isNull()
        assertThat(bundleRepository.existsById(draft.id)).isFalse()
    }

    @Test
    fun `rejoining participant cannot save with the original zero comment version`() {
        val owner = member()
        val participant = member()
        val bunchId = bunchWithMembers(owner, participant)
        val source = photo(bunchId, uploadedBy = listOf(owner))
        val draft = createDraft(owner, bunchId, listOf(source.id))

        val added = bundleService.addParticipant(actor(owner), bunchId, draft.id, participant, "aba-add")
        val initialCommentVersion = added.comments.first { it.memberId == participant }.version
        assertThat(initialCommentVersion).isGreaterThan(0)
        bundleService.comment(
            actor(participant),
            bunchId,
            draft.id,
            BundleTextRequest("before removal", initialCommentVersion, BundleStatus.DRAFT),
            "aba-before-removal"
        )

        bundleService.removeParticipant(actor(owner), bunchId, draft.id, participant, "aba-remove")
        val readded = bundleService.addParticipant(actor(owner), bunchId, draft.id, participant, "aba-readd")
        val currentCommentVersion = readded.comments.first { it.memberId == participant }.version
        assertThat(currentCommentVersion).isGreaterThan(0)
        assertThat(readded.comments.first { it.memberId == participant }.content).isEmpty()

        assertBundleError(ErrorCode.BUNDLE_VERSION_CONFLICT) {
            bundleService.comment(
                actor(participant),
                bunchId,
                draft.id,
                BundleTextRequest("stale rejoin save", 0, BundleStatus.DRAFT),
                "aba-stale-save"
            )
        }

        val saved = bundleService.comment(
            actor(participant),
            bunchId,
            draft.id,
            BundleTextRequest("fresh rejoin save", currentCommentVersion, BundleStatus.DRAFT),
            "aba-fresh-save"
        )
        assertThat(saved.comments.first { it.memberId == participant }.content).isEqualTo("fresh rejoin save")
    }

    @Test
    fun `date chips rank valid captured dates and disappear after a date is selected`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val dayOne = LocalDate.of(2024, 1, 10)
        val dayTwo = LocalDate.of(2024, 1, 11)
        val dayThree = LocalDate.of(2024, 1, 12)
        val dayFour = LocalDate.of(2024, 1, 13)
        val future = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1)
        val selected = listOf(
            photo(bunchId, listOf(owner), dayOne),
            photo(bunchId, listOf(owner), dayOne),
            photo(bunchId, listOf(owner), dayTwo),
            photo(bunchId, listOf(owner), dayTwo),
            photo(bunchId, listOf(owner), dayTwo),
            photo(bunchId, listOf(owner), dayThree),
            photo(bunchId, listOf(owner), dayFour),
            photo(bunchId, listOf(owner), future),
            photo(bunchId, listOf(owner), null)
        )

        val draft = createDraft(owner, bunchId, selected.map { it.id })
        assertThat(draft.dateChips).containsExactly(dayTwo, dayOne, dayThree)

        val token = bundleLeases.acquire(actor(owner), bunchId, draft.id, "DATE", "date-connection").token
        val dated = bundleService.date(
            actor(owner),
            bunchId,
            draft.id,
            BundleDateRequest(dayFour, 0, BundleStatus.DRAFT, token),
            "set-date"
        )
        assertThat(dated.date).isEqualTo(dayFour)
        assertThat(dated.dateChips).isEmpty()
    }

    @Test
    fun `concurrent photo changes merge disjoint differences against the latest order`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val original = (1..3).map { photo(bunchId, listOf(owner)) }
        val additions = (1..2).map { photo(bunchId, listOf(owner)) }
        val draft = createDraft(owner, bunchId, original.map { it.id })
        bundleService.addParticipant(actor(owner), bunchId, draft.id, guest, "add-guest")
        val rows = bundlePhotos.findAllByBundleIdOrderByPositionAscIdAsc(draft.id)

        val results = concurrently(
            listOf(
                {
                    bundleService.changePhotos(
                        actor(owner),
                        bunchId,
                        draft.id,
                        UUID.randomUUID(),
                        BundlePhotoChangesRequest(
                            addPhotoIds = listOf(additions[0].id),
                            removeBundlePhotoIds = listOf(rows[0].id),
                            mode = BundleStatus.DRAFT
                        )
                    )
                },
                {
                    bundleService.changePhotos(
                        actor(guest),
                        bunchId,
                        draft.id,
                        UUID.randomUUID(),
                        BundlePhotoChangesRequest(
                            addPhotoIds = listOf(additions[1].id),
                            removeBundlePhotoIds = listOf(rows[1].id),
                            mode = BundleStatus.DRAFT
                        )
                    )
                }
            )
        )

        assertThat(results.all { it.isSuccess }).isTrue()
        val final = bundleService.get(owner, bunchId, draft.id)
        assertThat(final.photos.map { it.photoId }).containsExactlyInAnyOrder(
            original[2].id,
            additions[0].id,
            additions[1].id
        )
        assertThat(final.photos.map { it.position }).containsExactlyElementsOf((0..2).toList())
    }

    @Test
    fun `picker reselection appends existing and new photos in selected number order without recreating rows`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val source = (1..5).map { photo(bunchId, listOf(owner)) }
        val draft = createDraft(owner, bunchId, source.take(3).map { it.id })
        val original = draft.photos.first()
        jdbc.update("UPDATE bundle_photos SET caption = 'keep caption', caption_version = 7 WHERE id = ?", original.id)

        val changed = bundleService.changePhotos(
            actor(owner), bunchId, draft.id, UUID.randomUUID(),
            BundlePhotoChangesRequest(
                addPhotoIds = listOf(source[3].id, source[0].id, source[4].id),
                mode = BundleStatus.DRAFT,
                reselectedPhotoIds = listOf(source[0].id)
            )
        )

        assertThat(changed.photos.map { it.photoId }).containsExactly(
            source[1].id, source[2].id, source[3].id, source[0].id, source[4].id
        )
        assertThat(changed.photos[3].id).isEqualTo(original.id)
        assertThat(changed.photos[3].caption).isEqualTo("keep caption")
        assertThat(changed.photos[3].captionVersion).isEqualTo(7)
        assertThat(changed.revision).isEqualTo(draft.revision + 1)
        assertThat(changed.photoChangeResult!!.addedPhotoIds).containsExactly(source[3].id, source[4].id)
    }

    @Test
    fun `picker reselection preserves concurrent moves additions and deletions`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val source = (1..6).map { photo(bunchId, listOf(owner)) }
        val draft = createDraft(owner, bunchId, source.take(4).map { it.id })
        val pending = BundlePhotoChangesRequest(
            addPhotoIds = listOf(source[5].id, source[1].id),
            removeBundlePhotoIds = listOf(draft.photos[2].id),
            mode = BundleStatus.DRAFT,
            reselectedPhotoIds = listOf(source[1].id)
        )
        bundleService.move(
            actor(owner), bunchId, draft.id, draft.photos[3].id,
            MoveBundlePhotoRequest(nextBundlePhotoId = draft.photos[0].id, mode = BundleStatus.DRAFT),
            "other-photo-move"
        )
        bundleService.changePhotos(
            actor(owner), bunchId, draft.id, UUID.randomUUID(),
            BundlePhotoChangesRequest(
                addPhotoIds = listOf(source[4].id),
                removeBundlePhotoIds = listOf(draft.photos[2].id),
                mode = BundleStatus.DRAFT
            )
        )

        val changed = bundleService.changePhotos(actor(owner), bunchId, draft.id, UUID.randomUUID(), pending)

        assertThat(changed.photos.map { it.photoId }).containsExactly(
            source[3].id, source[0].id, source[4].id, source[5].id, source[1].id
        )
        assertThat(changed.photos.map { it.position }).containsExactly(0, 1, 2, 3, 4)
    }

    @Test
    fun `picker reselection never readds a photo removed while picker was open`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val source = (1..3).map { photo(bunchId, listOf(owner)) }
        val draft = createDraft(owner, bunchId, source.take(2).map { it.id })
        bundleService.changePhotos(
            actor(owner), bunchId, draft.id, UUID.randomUUID(),
            BundlePhotoChangesRequest(removeBundlePhotoIds = listOf(draft.photos[0].id), mode = BundleStatus.DRAFT)
        )

        val changed = bundleService.changePhotos(
            actor(owner), bunchId, draft.id, UUID.randomUUID(),
            BundlePhotoChangesRequest(
                addPhotoIds = listOf(source[0].id, source[2].id),
                mode = BundleStatus.DRAFT,
                reselectedPhotoIds = listOf(source[0].id)
            )
        )

        assertThat(changed.photos.map { it.photoId }).containsExactly(source[1].id, source[2].id)
    }

    @Test
    fun `picker reselection retries never undo later moves and new intent conflicts with reused key`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val source = (1..3).map { photo(bunchId, listOf(owner)) }
        val draft = createDraft(owner, bunchId, source.map { it.id })
        val key = UUID.randomUUID()
        val request = BundlePhotoChangesRequest(
            addPhotoIds = listOf(source[0].id), mode = BundleStatus.DRAFT,
            reselectedPhotoIds = listOf(source[0].id)
        )
        bundleService.changePhotos(actor(owner), bunchId, draft.id, key, request)
        val moved = bundleService.move(
            actor(owner), bunchId, draft.id, draft.photos[0].id,
            MoveBundlePhotoRequest(nextBundlePhotoId = draft.photos[1].id, mode = BundleStatus.DRAFT),
            "after-picker-move"
        )
        val replayed = bundleService.changePhotos(actor(owner), bunchId, draft.id, key, request)
        assertThat(replayed.photos).isEqualTo(moved.photos)
        assertThat(replayed.revision).isEqualTo(moved.revision)
        assertThat(replayed.photoChangeResult!!.replayed).isTrue()
        assertBundleError(ErrorCode.IDEMPOTENCY_CONFLICT) {
            bundleService.changePhotos(actor(owner), bunchId, draft.id, key, request.copy(reselectedPhotoIds = emptyList()))
        }
    }

    @Test
    fun `picker reselection invalidates old move gap without affecting later gap moves`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val source = (1..4).map { photo(bunchId, listOf(owner)) }
        val draft = createDraft(owner, bunchId, source.map { it.id })
        bundleService.move(
            actor(owner), bunchId, draft.id, draft.photos[2].id,
            MoveBundlePhotoRequest(previousBundlePhotoId = draft.photos[0].id, mode = BundleStatus.DRAFT),
            "before-picker-gap"
        )
        bundleService.changePhotos(
            actor(owner), bunchId, draft.id, UUID.randomUUID(),
            BundlePhotoChangesRequest(
                addPhotoIds = listOf(source[2].id), mode = BundleStatus.DRAFT,
                reselectedPhotoIds = listOf(source[2].id)
            )
        )
        val moved = bundleService.move(
            actor(owner), bunchId, draft.id, draft.photos[1].id,
            MoveBundlePhotoRequest(previousBundlePhotoId = draft.photos[0].id, mode = BundleStatus.DRAFT),
            "after-picker-gap"
        )
        assertThat(moved.photos.map { it.photoId }).containsExactly(source[0].id, source[1].id, source[3].id, source[2].id)
    }

    @Test
    fun `picker reselection capacity failure rolls back removal order and receipt`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val source = (1..22).map { photo(bunchId, listOf(owner)) }
        val draft = createDraft(owner, bunchId, source.take(20).map { it.id })
        val key = UUID.randomUUID()
        assertBundleError(ErrorCode.BUNDLE_PHOTO_CONFLICT) {
            bundleService.changePhotos(
                actor(owner), bunchId, draft.id, key,
                BundlePhotoChangesRequest(
                    addPhotoIds = listOf(source[20].id, source[0].id, source[21].id),
                    removeBundlePhotoIds = listOf(draft.photos[1].id),
                    mode = BundleStatus.DRAFT,
                    reselectedPhotoIds = listOf(source[0].id)
                )
            )
        }
        val after = bundleService.get(owner, bunchId, draft.id)
        assertThat(after.photos).isEqualTo(draft.photos)
        assertThat(after.revision).isEqualTo(draft.revision)
        assertThat(after.updatedAt).isEqualTo(draft.updatedAt)
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM bundle_requests WHERE request_key = ?", Long::class.java, key
        )).isZero()
    }

    @Test
    fun `picker reselection validates subset duplicates and partial mode without mutation`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val source = (1..3).map { photo(bunchId, listOf(owner)) }
        val draft = createDraft(owner, bunchId, source.map { it.id })
        val base = BundlePhotoChangesRequest(
            addPhotoIds = listOf(source[0].id), mode = BundleStatus.DRAFT,
            reselectedPhotoIds = listOf(source[0].id)
        )
        val invalid = listOf(
            base.copy(reselectedPhotoIds = listOf(source[1].id)),
            base.copy(reselectedPhotoIds = listOf(source[0].id, source[0].id)),
            base.copy(capacityMode = com.bunch.bundle.domain.BundlePhotoCapacityMode.PARTIAL_ADD)
        )
        invalid.forEach { request ->
            assertBundleError(ErrorCode.INVALID_INPUT) {
                bundleService.changePhotos(actor(owner), bunchId, draft.id, UUID.randomUUID(), request)
            }
        }
        assertThat(bundleService.get(owner, bunchId, draft.id).photos).isEqualTo(draft.photos)
    }

    @Test
    fun `picker reselection of last photo is a no op and preserves last photo protection`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val source = photo(bunchId, listOf(owner))
        val draft = createDraft(owner, bunchId, listOf(source.id))
        val request = BundlePhotoChangesRequest(
            addPhotoIds = listOf(source.id), mode = BundleStatus.DRAFT, reselectedPhotoIds = listOf(source.id)
        )
        val repeated = bundleService.changePhotos(actor(owner), bunchId, draft.id, UUID.randomUUID(), request)
        assertThat(repeated.photos).isEqualTo(draft.photos)
        assertThat(repeated.revision).isEqualTo(draft.revision)
        assertBundleError(ErrorCode.BUNDLE_PHOTO_CONFLICT) {
            bundleService.changePhotos(
                actor(owner), bunchId, draft.id, UUID.randomUUID(),
                BundlePhotoChangesRequest(removeBundlePhotoIds = listOf(draft.photos.single().id), mode = BundleStatus.DRAFT)
            )
        }
    }

    @Test
    fun `concurrent removal protects the final bundle photo`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val source = listOf(photo(bunchId, listOf(owner)), photo(bunchId, listOf(owner)))
        val draft = createDraft(owner, bunchId, source.map { it.id })
        bundleService.addParticipant(actor(owner), bunchId, draft.id, guest, "add-guest")
        val rows = bundlePhotos.findAllByBundleIdOrderByPositionAscIdAsc(draft.id)

        val results = concurrently(
            listOf(
                {
                    bundleService.changePhotos(
                        actor(owner),
                        bunchId,
                        draft.id,
                        UUID.randomUUID(),
                        BundlePhotoChangesRequest(
                            removeBundlePhotoIds = listOf(rows[0].id),
                            mode = BundleStatus.DRAFT
                        )
                    )
                },
                {
                    bundleService.changePhotos(
                        actor(guest),
                        bunchId,
                        draft.id,
                        UUID.randomUUID(),
                        BundlePhotoChangesRequest(
                            removeBundlePhotoIds = listOf(rows[1].id),
                            mode = BundleStatus.DRAFT
                        )
                    )
                }
            )
        )

        assertThat(results.all { result ->
            result.isSuccess || errorCode(result.exceptionOrNull()) == ErrorCode.BUNDLE_PHOTO_CONFLICT
        }).isTrue()
        assertThat(bundlePhotos.findAllByBundleIdOrderByPositionAscIdAsc(draft.id)).hasSize(1)
    }

    @Test
    fun `move uses current anchors and captions require the owning photo lease`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val source = (1..3).map { photo(bunchId, listOf(owner)) }
        val draft = createDraft(owner, bunchId, source.map { it.id })
        bundleService.addParticipant(actor(owner), bunchId, draft.id, guest, "add-guest")
        val rows = bundlePhotos.findAllByBundleIdOrderByPositionAscIdAsc(draft.id)

        val moved = bundleService.move(
            actor(owner),
            bunchId,
            draft.id,
            rows[2].id,
            MoveBundlePhotoRequest(nextBundlePhotoId = rows[0].id, mode = BundleStatus.DRAFT),
            "move-first"
        )
        assertThat(moved.photos.map { it.photoId }).containsExactly(source[2].id, source[0].id, source[1].id)
        assertBundleError(ErrorCode.BUNDLE_PHOTO_CONFLICT) {
            bundleService.move(
                actor(owner),
                bunchId,
                draft.id,
                rows[1].id,
                MoveBundlePhotoRequest(previousBundlePhotoId = Long.MAX_VALUE, mode = BundleStatus.DRAFT),
                "stale-anchor"
            )
        }

        val target = moved.photos.first()
        val token = bundleLeases.acquire(
            actor(owner),
            bunchId,
            draft.id,
            "PHOTO:${target.id}",
            "caption-connection"
        ).token
        assertBundleError(ErrorCode.BUNDLE_LEASE_CONFLICT) {
            bundleService.caption(
                actor(guest),
                bunchId,
                draft.id,
                target.id,
                BundleTextRequest("foreign caption", 0, BundleStatus.DRAFT),
                "foreign-caption"
            )
        }
        val captioned = bundleService.caption(
            actor(owner),
            bunchId,
            draft.id,
            target.id,
            BundleTextRequest("caption belongs to owner", 0, BundleStatus.DRAFT, token),
            "owner-caption"
        )
        assertThat(captioned.photos.first { it.id == target.id }.caption).isEqualTo("caption belongs to owner")
        assertThat(captioned.leases).extracting<Long> { it.memberId }.contains(owner)
    }

    @Test
    fun `legacy title save ignores expired lease and relies on field version`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val source = photo(bunchId, listOf(owner))
        val draft = createDraft(owner, bunchId, listOf(source.id))
        bundleService.addParticipant(actor(owner), bunchId, draft.id, guest, "add-guest")

        val firstActor = actor(owner, "session-old")
        val first = bundleLeases.acquire(firstActor, bunchId, draft.id, "TITLE", "connection-old")
        jdbc.update(
            """UPDATE bundle_edit_leases
               SET last_heartbeat_at = CURRENT_TIMESTAMP - INTERVAL '16 seconds'
               WHERE bundle_id = ? AND target = 'TITLE'""",
            draft.id
        )
        val secondActor = actor(guest, "session-new")
        val second = bundleLeases.acquire(secondActor, bunchId, draft.id, "TITLE", "connection-new")
        assertThat(second.token).isNotEqualTo(first.token)
        assertThat(second.leaseVersion).isEqualTo(first.leaseVersion + 1)

        val firstSaved = bundleService.title(
            firstActor, bunchId, draft.id,
            BundleTextRequest("late old title", 0, BundleStatus.DRAFT, first.token), "late-old-save"
        )
        val saved = bundleService.title(
            secondActor,
            bunchId,
            draft.id,
            BundleTextRequest("new title", firstSaved.titleVersion, BundleStatus.DRAFT, second.token),
            "new-save"
        )
        assertThat(saved.title).isEqualTo("new title")
    }

    @Test
    fun `active lease can rotate only on the same connection and stale operations cannot use the old token`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val source = photo(bunchId, listOf(owner))
        val draft = createDraft(owner, bunchId, listOf(source.id))
        val actor = actor(owner, "shared-session")

        val first = bundleLeases.acquire(actor, bunchId, draft.id, "TITLE", "connection-a")
        val rotated = bundleLeases.acquire(
            actor,
            bunchId,
            draft.id,
            "TITLE",
            "connection-a"
        )

        assertThat(rotated.token).isNotEqualTo(first.token)
        assertThat(rotated.leaseVersion).isEqualTo(first.leaseVersion + 1)
        assertThat(rotated.revision).isEqualTo(first.revision)

        assertBundleError(ErrorCode.BUNDLE_LEASE_CONFLICT) {
            bundleLeases.acquire(actor, bunchId, draft.id, "TITLE", "connection-b")
        }
        assertBundleError(ErrorCode.BUNDLE_LEASE_CONFLICT) {
            bundleLeases.heartbeat(
                actor = actor,
                bunchId = bunchId,
                bundleId = draft.id,
                target = "TITLE",
                token = first.token,
                connectionId = "connection-a"
            )
        }
        assertBundleError(ErrorCode.BUNDLE_LEASE_CONFLICT) {
            bundleLeases.release(
                actor = actor,
                bunchId = bunchId,
                bundleId = draft.id,
                target = "TITLE",
                token = first.token,
                connectionId = "connection-a"
            )
        }
        val staleTokenSave = bundleService.title(
            actor, bunchId, draft.id,
            BundleTextRequest("stale title", 0, BundleStatus.DRAFT, first.token), "stale-token-save"
        )

        val saved = bundleService.title(
            actor,
            bunchId,
            draft.id,
            BundleTextRequest("current title", staleTokenSave.titleVersion, BundleStatus.DRAFT, rotated.token),
            "current-token-save"
        )
        assertThat(saved.title).isEqualTo("current title")
    }

    @Test
    fun `concurrent acquire for one member session still permits only one connection`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val source = photo(bunchId, listOf(owner))
        val draft = createDraft(owner, bunchId, listOf(source.id))
        val actor = actor(owner, "concurrent-session")

        val results = concurrently(
            listOf(
                { bundleLeases.acquire(actor, bunchId, draft.id, "TITLE", "connection-a") },
                { bundleLeases.acquire(actor, bunchId, draft.id, "TITLE", "connection-b") }
            )
        )

        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(results.count { errorCode(it.exceptionOrNull()) == ErrorCode.BUNDLE_LEASE_CONFLICT }).isEqualTo(1)
    }

    @Test
    fun `late disconnect from an expired connection cannot release its replacement lease`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val source = photo(bunchId, listOf(owner))
        val draft = createDraft(owner, bunchId, listOf(source.id))
        val first = bundleLeases.acquire(
            actor(owner, "shared-session"), bunchId, draft.id, "TITLE", "connection-old"
        )
        jdbc.update(
            """UPDATE bundle_edit_leases
               SET last_heartbeat_at = CURRENT_TIMESTAMP - INTERVAL '16 seconds'
               WHERE bundle_id = ? AND target = 'TITLE'""",
            draft.id
        )

        val replacement = bundleLeases.acquire(
            actor(owner, "shared-session"), bunchId, draft.id, "TITLE", "connection-new"
        )
        assertThat(replacement.leaseVersion).isEqualTo(first.leaseVersion + 1)

        bundleLeases.clearConnection("connection-old")

        val current = bundleLeases.publicStates(draft.id).single()
        assertThat(current.memberId).isEqualTo(owner)
        assertThat(current.leaseVersion).isEqualTo(replacement.leaseVersion)
    }

    @Test
    fun `disconnect before a blocked acquire commits cannot leave an active lease`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val draft = createDraft(owner, bunchId, listOf(photo(bunchId, listOf(owner)).id))
        val connectionId = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()
        realtimeRegistry.bind(connectionId, BundleActor(owner, sessionId))
        val headers = StompHeaderAccessor.create(StompCommand.SEND).apply {
            this.sessionId = connectionId
            user = UsernamePasswordAuthenticationToken(owner.toString(), null).apply {
                details = JwtSessionDetails(sessionId)
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending = TransactionTemplate(transactionManager).execute {
                bunchRepository.findLocked(bunchId)
                val acquisition = executor.submit<Throwable?> {
                    runCatching {
                        socketController.acquire(headers, bunchId, draft.id, BundleLeaseCommand("TITLE"))
                    }.exceptionOrNull()
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (true) {
                    jdbc.execute("SELECT pg_stat_clear_snapshot()")
                    val waiting = jdbc.queryForObject(
                        """SELECT count(*) FROM pg_stat_activity
                           WHERE datname = current_database() AND wait_event_type = 'Lock'
                           AND lower(query) LIKE '%bunches%'""",
                        Long::class.java
                    ) ?: 0L
                    if (waiting > 0) break
                    check(!acquisition.isDone) { "Acquire completed before waiting: ${acquisition.get()}" }
                    check(System.nanoTime() < deadline) { "Acquire did not wait for the bunch lock" }
                    Thread.sleep(10)
                }
                realtimeRegistry.remove(connectionId)
                bundleLeases.clearConnection(connectionId)
                acquisition
            }!!
            val error = pending.get(5, TimeUnit.SECONDS)
            assertThat(error).isInstanceOf(BusinessException::class.java)
            assertThat((error as BusinessException).errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
            assertThat(bundleLeases.publicStates(draft.id)).isEmpty()
        } finally {
            realtimeRegistry.remove(connectionId)
            executor.shutdownNow()
        }
    }

    @Test
    fun `field version conflict returns current state without a stale write`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val source = photo(bunchId, listOf(owner))
        val draft = createDraft(owner, bunchId, listOf(source.id))
        val token = bundleLeases.acquire(actor(owner), bunchId, draft.id, "TITLE", "version-connection").token

        val first = bundleService.title(
            actor(owner),
            bunchId,
            draft.id,
            BundleTextRequest("first title", 0, BundleStatus.DRAFT, token),
            "first-title"
        )
        assertBundleError(ErrorCode.BUNDLE_VERSION_CONFLICT) {
            bundleService.title(
                actor(owner),
                bunchId,
                draft.id,
                BundleTextRequest("stale title", 0, BundleStatus.DRAFT, token),
                "stale-title"
            )
        }
        val current = bundleService.get(owner, bunchId, draft.id)
        assertThat(current.title).isEqualTo(first.title)
        assertThat(current.titleVersion).isEqualTo(1)
    }

    @Test
    fun `failed participant insert rolls back the bundle graph atomically`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val source = photo(bunchId, listOf(owner))
        rejectWrite("bundle_test_reject_participant", "bundle_participants", "INSERT")

        assertThatThrownBy {
            bundleService.create(
                actor(owner),
                bunchId,
                UUID.randomUUID(),
                CreateBundleRequest(listOf(source.id))
            )
        }.isNotNull

        assertThat(bundleRepository.countByBunchIdAndStatus(bunchId, BundleStatus.DRAFT)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bundle_photos", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bundle_participants", Long::class.java)).isZero()
        assertThat(photos.existsById(source.id)).isTrue()
    }

    @Test
    fun `failed photo merge rolls back removal and addition together`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val original = listOf(photo(bunchId, listOf(owner)), photo(bunchId, listOf(owner)))
        val addition = photo(bunchId, listOf(owner))
        val draft = createDraft(owner, bunchId, original.map { it.id })
        bundleService.addParticipant(actor(owner), bunchId, draft.id, guest, "add-guest")
        val before = bundlePhotos.findAllByBundleIdOrderByPositionAscIdAsc(draft.id)
        val revisionBefore = bundleService.get(owner, bunchId, draft.id).revision
        rejectWrite("bundle_test_reject_photo", "bundle_photos", "INSERT")

        assertThatThrownBy {
            bundleService.changePhotos(
                actor(owner),
                bunchId,
                draft.id,
                UUID.randomUUID(),
                BundlePhotoChangesRequest(
                    addPhotoIds = listOf(addition.id),
                    removeBundlePhotoIds = listOf(before.first().id),
                    mode = BundleStatus.DRAFT
                )
            )
        }.isNotNull

        val after = bundleService.get(owner, bunchId, draft.id)
        assertThat(after.revision).isEqualTo(revisionBefore)
        assertThat(after.photos.map { it.photoId }).containsExactlyElementsOf(before.map { it.photoId })
        assertThat(photos.existsById(original.first().id)).isTrue()
        assertThat(photos.existsById(addition.id)).isTrue()
    }

    @Test
    fun `injected bundle callback failure rolls back final uploader removal`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val source = photo(bunchId, listOf(owner))
        val draft = createDraft(owner, bunchId, listOf(source.id))
        rejectWrite("bundle_test_reject_photo", "bundle_photos", "DELETE")

        assertThatThrownBy {
            // The three-argument overload must still run the injected BundlePhotoDeletionCallback.
            photoService.removeUploader(bunchId, source.id, owner)
        }.isNotNull

        assertThat(photos.existsById(source.id)).isTrue()
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM photo_uploaders WHERE photo_id = ?",
            Long::class.java,
            source.id
        )).isEqualTo(1)
        assertThat(bundlePhotos.findAllByBundleIdOrderByPositionAscIdAsc(draft.id)).hasSize(1)
        assertThat(assets.existsById(source.imageAsset.id)).isTrue()
        verifyNoInteractions(storage)
    }

    @Test
    fun `final source photo deletion removes the empty draft but keeps photo deletion atomic`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val source = photo(bunchId, uploadedBy = listOf(owner))
        val draft = createDraft(owner, bunchId, listOf(source.id))
        val assetPublicId = source.imageAsset.publicId

        val result = photoService.removeUploader(bunchId, source.id, owner)

        assertThat(result.uploaderRemoved).isTrue()
        assertThat(result.photoDeleted).isTrue()
        assertThat(bundleRepository.existsById(draft.id)).isFalse()
        assertThat(bundlePhotos.findAllByBundleIdOrderByPositionAscIdAsc(draft.id)).isEmpty()
        assertThat(photos.existsById(source.id)).isFalse()
        assertThat(assets.existsById(source.imageAsset.id)).isFalse()
        verify(storage, times(1)).delete(assetPublicId)
    }

    @Test
    fun `member departure removes participation and creator drafts`() {
        val owner = member()
        val participant = member()
        val remaining = member()
        val bunchId = bunchWithMembers(owner, participant, remaining)
        val participantPhoto = photo(bunchId, uploadedBy = listOf(owner, participant, remaining))
        val participantDraft = createDraft(owner, bunchId, listOf(participantPhoto.id))
        bundleService.addParticipant(actor(owner), bunchId, participantDraft.id, participant, "departure-add")
        val departureCommentVersion = bundleService.get(owner, bunchId, participantDraft.id)
            .comments.firstOrNull { it.memberId == participant }?.version ?: 0
        bundleService.comment(
            actor(participant),
            bunchId,
            participantDraft.id,
            BundleTextRequest("participant before departure", departureCommentVersion, BundleStatus.DRAFT),
            "departure-comment"
        )
        bundleLeases.acquire(actor(participant), bunchId, participantDraft.id, "TITLE", "departure-connection")

        inTransaction { bundleService.memberDeparted(bunchId, participant) }

        val afterParticipantDeparture = bundleService.get(owner, bunchId, participantDraft.id)
        assertThat(afterParticipantDeparture.participants.map { it.memberId }).containsExactly(owner, remaining)
        assertThat(afterParticipantDeparture.comments).isEmpty()
        assertThat(afterParticipantDeparture.leases).isEmpty()
        assertThat(bundlePhotos.findAllByBundleIdOrderByPositionAscIdAsc(participantDraft.id)).hasSize(1)
        assertThat(photos.existsById(participantPhoto.id)).isTrue()
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM bundle_edit_leases WHERE bundle_id = ? AND member_id = ?",
            Long::class.java,
            participantDraft.id,
            participant
        )).isEqualTo(1)
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM bundle_edit_leases " +
                "WHERE bundle_id = ? AND member_id = ? AND released_at IS NULL",
            Long::class.java,
            participantDraft.id,
            participant
        )).isZero()

        val creatorPhoto = photo(bunchId, uploadedBy = listOf(remaining))
        val creatorDraft = createDraft(owner, bunchId, listOf(creatorPhoto.id))
        inTransaction { bundleService.memberDeparted(bunchId, owner) }

        assertThat(bundleRepository.existsById(participantDraft.id)).isFalse()
        assertThat(bundleRepository.existsById(creatorDraft.id)).isFalse()
        assertThat(photos.existsById(participantPhoto.id)).isTrue()
        assertThat(photos.existsById(creatorPhoto.id)).isTrue()
    }

    @Test
    fun `member departure cleanup rolls back participation comment and lease together`() {
        val owner = member()
        val participant = member()
        val bunchId = bunchWithMembers(owner, participant)
        val source = photo(bunchId, uploadedBy = listOf(owner, participant))
        val draft = createDraft(owner, bunchId, listOf(source.id))
        bundleService.addParticipant(actor(owner), bunchId, draft.id, participant, "rollback-add")
        val rollbackCommentVersion = bundleService.get(owner, bunchId, draft.id)
            .comments.firstOrNull { it.memberId == participant }?.version ?: 0
        bundleService.comment(
            actor(participant),
            bunchId,
            draft.id,
            BundleTextRequest("must survive rollback", rollbackCommentVersion, BundleStatus.DRAFT),
            "rollback-comment"
        )
        bundleLeases.acquire(actor(participant), bunchId, draft.id, "TITLE", "rollback-connection")
        rejectWrite("bundle_test_reject_participant", "bundle_participants", "DELETE")

        assertThatThrownBy {
            inTransaction { bundleService.memberDeparted(bunchId, participant) }
        }.isNotNull

        val afterRollback = bundleService.get(owner, bunchId, draft.id)
        assertThat(afterRollback.participants.map { it.memberId }).containsExactly(owner, participant)
        assertThat(afterRollback.comments.map { it.memberId }).containsExactly(participant)
        assertThat(afterRollback.leases.map { it.target }).contains("TITLE")
        assertThat(bundlePhotos.findAllByBundleIdOrderByPositionAscIdAsc(draft.id)).hasSize(1)
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM photo_uploaders WHERE photo_id = ? AND member_id = ?",
            Long::class.java,
            source.id,
            participant
        )).isEqualTo(1)
    }

    @Test
    fun `create idempotency replays, rejects conflicting input, and preserves deleted result`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val first = photo(bunchId, listOf(owner))
        val second = photo(bunchId, listOf(owner))
        val key = UUID.randomUUID()
        val request = CreateBundleRequest(listOf(first.id))

        val created = bundleService.create(actor(owner), bunchId, key, request)
        val replay = bundleService.create(actor(owner), bunchId, key, request)
        assertThat(replay.id).isEqualTo(created.id)
        assertThat(bundleRepository.countByBunchIdAndStatus(bunchId, BundleStatus.DRAFT)).isEqualTo(1)
        assertThat(bundleParticipants.findAllByBundleIdOrderByJoinedAtAscIdAsc(created.id)).hasSize(1)
        assertBundleError(ErrorCode.IDEMPOTENCY_CONFLICT) {
            bundleService.create(actor(owner), bunchId, key, CreateBundleRequest(listOf(second.id)))
        }

        bundleService.delete(actor(owner), bunchId, created.id, "delete-idempotent-create")
        assertThat(bundleRepository.existsById(created.id)).isFalse()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bundle_requests WHERE bundle_id = ?", Long::class.java, created.id))
            .isEqualTo(1)
        assertBundleError(ErrorCode.RESOURCE_NOT_FOUND) {
            bundleService.create(actor(owner), bunchId, key, request)
        }
    }

    @Test
    fun `photo changes are idempotent and conflicting input is rejected`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val original = photo(bunchId, listOf(owner))
        val addition = photo(bunchId, listOf(owner))
        val otherAddition = photo(bunchId, listOf(owner))
        val draft = createDraft(owner, bunchId, listOf(original.id))
        val key = UUID.randomUUID()
        val request = BundlePhotoChangesRequest(listOf(addition.id), mode = BundleStatus.DRAFT)

        val changed = bundleService.changePhotos(actor(owner), bunchId, draft.id, key, request)
        val replay = bundleService.changePhotos(actor(owner), bunchId, draft.id, key, request)
        assertThat(replay.photos.map { it.photoId }).containsExactlyElementsOf(changed.photos.map { it.photoId })
        assertThat(bundlePhotos.findAllByBundleIdOrderByPositionAscIdAsc(draft.id)).hasSize(2)
        assertBundleError(ErrorCode.IDEMPOTENCY_CONFLICT) {
            bundleService.changePhotos(
                actor(owner),
                bunchId,
                draft.id,
                key,
                BundlePhotoChangesRequest(listOf(otherAddition.id), mode = BundleStatus.DRAFT)
            )
        }
    }

    @Test
    fun `photo changes reject adding and removing the same source photo`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val source = photo(bunchId, listOf(owner))
        val draft = createDraft(owner, bunchId, listOf(source.id))
        val row = bundlePhotos.findAllByBundleIdOrderByPositionAscIdAsc(draft.id).single()
        val lease = bundleLeases.acquire(
            actor(owner),
            bunchId,
            draft.id,
            "PHOTO:${row.id}",
            "overlap-caption-connection"
        ).token
        val captioned = bundleService.caption(
            actor(owner),
            bunchId,
            draft.id,
            row.id,
            BundleTextRequest("keep this caption", 0, BundleStatus.DRAFT, lease),
            "overlap-caption"
        )

        assertBundleError(ErrorCode.INVALID_INPUT) {
            bundleService.changePhotos(
                actor(owner),
                bunchId,
                draft.id,
                UUID.randomUUID(),
                BundlePhotoChangesRequest(
                    addPhotoIds = listOf(source.id),
                    removeBundlePhotoIds = listOf(row.id),
                    mode = BundleStatus.DRAFT
                )
            )
        }

        val after = bundleService.get(owner, bunchId, draft.id)
        assertThat(after.revision).isEqualTo(captioned.revision)
        assertThat(after.photos).hasSize(1)
        assertThat(after.photos.single().id).isEqualTo(row.id)
        assertThat(after.photos.single().photoId).isEqualTo(source.id)
        assertThat(after.photos.single().caption).isEqualTo("keep this caption")
    }

    @Test
    fun `publish rechecks conditions, supports retries, and rejects late draft saves`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val source = photo(bunchId, listOf(owner))
        val draft = createDraft(owner, bunchId, listOf(source.id))
        val initialRevision = draft.revision

        assertBundleError(ErrorCode.BUNDLE_PUBLISH_BLOCKED) {
            bundleService.publish(
                actor(owner),
                bunchId,
                draft.id,
                UUID.randomUUID(),
                PublishBundleRequest(initialRevision)
            )
        }
        assertThat(bundleService.get(owner, bunchId, draft.id).status).isEqualTo(BundleStatus.DRAFT)

        bundleService.addParticipant(actor(owner), bunchId, draft.id, guest, "publish-add-guest")
        val guestCommentVersion = bundleService.get(owner, bunchId, draft.id)
            .comments.firstOrNull { it.memberId == guest }?.version ?: 0
        val titleGrant = bundleLeases.acquire(actor(owner), bunchId, draft.id, "TITLE", "publish-title").token
        bundleService.title(
            actor(owner),
            bunchId,
            draft.id,
            BundleTextRequest("publishable", 0, BundleStatus.DRAFT, titleGrant),
            "publish-title-save"
        )
        bundleService.visibility(
            actor(owner),
            bunchId,
            draft.id,
            BundleVisibilityRequest(Visibility.PRIVATE, 0, BundleStatus.DRAFT),
            "publish-visibility"
        )
        bundleService.comment(
            actor(owner),
            bunchId,
            draft.id,
            BundleTextRequest("owner comment", 0, BundleStatus.DRAFT),
            "publish-owner-comment"
        )
        bundleService.comment(
            actor(guest),
            bunchId,
            draft.id,
            BundleTextRequest("guest comment", guestCommentVersion, BundleStatus.DRAFT),
            "publish-guest-comment"
        )

        val beforePublish = bundleService.get(owner, bunchId, draft.id)
        val key = UUID.randomUUID()
        val published = bundleService.publish(
            actor(owner),
            bunchId,
            draft.id,
            key,
            PublishBundleRequest(beforePublish.revision)
        )
        assertThat(published.status).isEqualTo(BundleStatus.PUBLISHED)

        val retry = bundleService.publish(
            actor(owner),
            bunchId,
            draft.id,
            key,
            PublishBundleRequest(beforePublish.revision)
        )
        assertThat(retry.status).isEqualTo(BundleStatus.PUBLISHED)
        assertThat(retry.id).isEqualTo(published.id)
        assertBundleError(ErrorCode.IDEMPOTENCY_CONFLICT) {
            bundleService.publish(
                actor(owner),
                bunchId,
                draft.id,
                key,
                PublishBundleRequest(beforePublish.revision + 1)
            )
        }
        assertBundleError(ErrorCode.BUNDLE_STATE_CONFLICT) {
            bundleService.title(
                actor(owner),
                bunchId,
                draft.id,
                BundleTextRequest("late draft save", published.titleVersion, BundleStatus.DRAFT, titleGrant),
                "late-draft-save"
            )
        }
    }

    @Test
    fun `concurrent publish requests with different keys publish exactly once`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val source = photo(bunchId, uploadedBy = listOf(owner))
        val draft = publishableDraft(owner, guest, bunchId, source, "two-keys")
        val expectedRevision = bundleService.get(owner, bunchId, draft.id).revision
        val keys = listOf(UUID.randomUUID(), UUID.randomUUID())

        val results = concurrently(
            keys.map { key ->
                {
                    bundleService.publish(
                        actor(owner),
                        bunchId,
                        draft.id,
                        key,
                        PublishBundleRequest(expectedRevision)
                    )
                }
            }
        )

        assertThat(results.all { it.isSuccess }).isTrue()
        assertThat(results.map { it.getOrThrow().status }).containsOnly(BundleStatus.PUBLISHED)
        assertThat(results.map { it.getOrThrow().id }).containsOnly(draft.id)
        assertThat(bundleRepository.countByBunchIdAndStatus(bunchId, BundleStatus.PUBLISHED)).isEqualTo(1)
        assertThat(bundleRepository.findById(draft.id).orElseThrow().revision).isEqualTo(expectedRevision + 1)
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM bundle_requests WHERE bundle_id = ? AND operation = ?",
            Long::class.java,
            draft.id,
            "PUBLISH:${draft.id}"
        )).isEqualTo(2)
    }

    @Test
    fun `concurrent publish and final source deletion serialize to one valid outcome`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val source = photo(bunchId, uploadedBy = listOf(owner))
        val draft = publishableDraft(owner, guest, bunchId, source, "publish-delete")
        val assetPublicId = source.imageAsset.publicId
        val expectedRevision = bundleService.get(owner, bunchId, draft.id).revision

        val results = concurrently(
            listOf<() -> Any>(
                {
                    bundleService.publish(
                        actor(owner),
                        bunchId,
                        draft.id,
                        UUID.randomUUID(),
                        PublishBundleRequest(expectedRevision)
                    )
                },
                { photoService.removeUploader(bunchId, source.id, owner) }
            )
        )

        assertThat(results[1].isSuccess).isTrue()
        val deletion = results[1].getOrThrow() as PhotoUploaderRemovalResult
        assertThat(deletion.photoDeleted).isTrue()
        assertThat(photos.existsById(source.id)).isFalse()

        if (results[0].isSuccess) {
            val published = results[0].getOrThrow() as BundleSnapshot
            assertThat(published.status).isEqualTo(BundleStatus.PUBLISHED)
            assertThat(bundleRepository.existsById(draft.id)).isTrue()
            assertThat(bundlePhotos.findAllByBundleIdOrderByPositionAscIdAsc(draft.id).single().photoId).isNull()
            assertThat(assets.existsById(source.imageAsset.id)).isTrue()
            verifyNoInteractions(storage)
        } else {
            assertThat(errorCode(results[0].exceptionOrNull())).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND)
            assertThat(bundleRepository.existsById(draft.id)).isFalse()
            assertThat(assets.existsById(source.imageAsset.id)).isFalse()
            verify(storage, times(1)).delete(assetPublicId)
        }
    }

    @Test
    fun `published bundles allow manager updates and retain image assets after source deletion`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val source = photo(bunchId, listOf(owner))
        val published = publishReadyBundle(owner, guest, bunchId, source, "published-update")
        val publishedPhoto = published.photos.single()
        val assetId = publishedPhoto.imageAssetId
        val assetPublicId = source.imageAsset.publicId

        val updated = bundleService.editPublished(
            actor(owner), bunchId, published.id,
            publishedEdit(published).copy(
                title = "updated title", visibility = Visibility.PUBLIC, date = LocalDate.of(2024, 2, 1),
                photos = listOf(PublishedBundlePhotoInput(bundlePhotoId = publishedPhoto.id, caption = "published caption"))
            ), "published-save"
        )
        assertThat(updated.title).isEqualTo("updated title")
        assertThat(updated.visibility).isEqualTo(Visibility.PUBLIC)
        assertThat(updated.date).isEqualTo(LocalDate.of(2024, 2, 1))
        assertThat(updated.photos.single().caption).isEqualTo("published caption")
        assertBundleError(ErrorCode.FORBIDDEN) {
            bundleService.editPublished(actor(guest), bunchId, published.id, publishedEdit(updated), "guest-save")
        }

        photoService.removeUploader(bunchId, source.id, owner)
        assertThat(photos.existsById(source.id)).isFalse()
        val retained = bundlePhotos.findById(publishedPhoto.id).orElseThrow()
        assertThat(retained.photoId).isNull()
        assertThat(assets.existsById(assetId)).isTrue()
        verifyNoInteractions(storage)

        assertBundleError(ErrorCode.FORBIDDEN) {
            bundleService.delete(actor(guest), bunchId, published.id, "guest-delete")
        }
        bundleService.delete(actor(owner), bunchId, published.id, "owner-delete")
        assertThat(bundleRepository.existsById(published.id)).isFalse()
        assertThat(assets.existsById(assetId)).isFalse()
        verify(storage, times(1)).delete(assetPublicId)
    }

    @Test
    fun `stage counts public and private publications at all four boundaries and falls on deletion`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        assertThat(bundleService.summary(owner, bunchId).stage).isEqualTo(1)

        val publishedIds = mutableListOf<Long>()
        val expectedStages = listOf(1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4)
        expectedStages.forEachIndexed { offset, expectedStage ->
            val source = photo(bunchId, listOf(owner))
            val published = publishReadyBundle(owner, guest, bunchId, source, "stage-$offset")
            if (offset % 2 == 0) {
                bundleService.editPublished(
                    actor(owner), bunchId, published.id,
                    publishedEdit(published).copy(visibility = Visibility.PUBLIC), "stage-public-$offset"
                )
            }
            publishedIds += published.id
            assertSummary(bunchId, publishedCount = (offset + 1).toLong(), stage = expectedStage)
        }

        publishedIds.reversed().forEach { id ->
            bundleService.delete(actor(owner), bunchId, id, "stage-delete-$id")
            val remaining = bundleRepository.countByBunchIdAndStatus(bunchId, BundleStatus.PUBLISHED)
            val expected = when {
                remaining >= 10 -> 4
                remaining >= 6 -> 3
                remaining >= 2 -> 2
                else -> 1
            }
            assertThat(bundleService.summary(owner, bunchId).stage).isEqualTo(expected)
        }
    }

    @Test
    fun `published common content belongs only to current owner and participant comments are immutable`() {
        val owner = member()
        val creator = member()
        val coOwner = member()
        val bunchId = bunchWithMembers(owner, creator, coOwner)
        setRole(bunchId, coOwner, MembershipRole.CO_OWNER)
        val source = photo(bunchId, listOf(creator))
        val published = publishReadyBundle(creator, coOwner, bunchId, source, "member-creator")
        val photoId = published.photos.single().id
        jdbc.update("UPDATE bunch_memberships SET last_activity_at = NULL WHERE bunch_id = ?", bunchId)

        listOf(creator, coOwner).forEach { memberId ->
            val permissions = bundleService.get(memberId, bunchId, published.id).permissions
            assertThat(permissions.administrator).isEqualTo(memberId == coOwner)
            assertThat(permissions.creator).isEqualTo(memberId == creator)
            assertThat(permissions.canEdit).isFalse()
            assertThat(permissions.canPublish).isFalse()
            assertThat(permissions.canDelete).isFalse()
            val writes = listOf<() -> Any?>(
                {
                    bundleService.title(actor(memberId), bunchId, published.id,
                        BundleTextRequest("forbidden", published.titleVersion, BundleStatus.PUBLISHED), "role-title")
                },
                {
                    bundleService.visibility(actor(memberId), bunchId, published.id,
                        BundleVisibilityRequest(Visibility.PUBLIC, published.visibilityVersion, BundleStatus.PUBLISHED),
                        "role-visibility")
                },
                {
                    bundleService.date(actor(memberId), bunchId, published.id,
                        BundleDateRequest(LocalDate.of(2024, 2, 1), 0, BundleStatus.PUBLISHED), "role-date")
                },
                {
                    bundleService.caption(actor(memberId), bunchId, published.id, photoId,
                        BundleTextRequest("forbidden", 0, BundleStatus.PUBLISHED), "role-caption")
                },
                {
                    bundleService.changePhotos(actor(memberId), bunchId, published.id, UUID.randomUUID(),
                        BundlePhotoChangesRequest(mode = BundleStatus.PUBLISHED))
                },
                {
                    bundleService.move(actor(memberId), bunchId, published.id, photoId,
                        MoveBundlePhotoRequest(null, null, BundleStatus.PUBLISHED), "role-move")
                },
                { bundleService.delete(actor(memberId), bunchId, published.id, "role-delete") }
            )
            writes.forEach { assertBundleError(ErrorCode.FORBIDDEN, it) }
            assertThat(lastActivity(bunchId, memberId)).isNull()
            val comment = bundleService.get(memberId, bunchId, published.id).comments.single { it.memberId == memberId }
            assertBundleError(ErrorCode.BUNDLE_STATE_CONFLICT) {
                bundleService.comment(actor(memberId), bunchId, published.id,
                    BundleTextRequest("my published comment", comment.version, BundleStatus.PUBLISHED), "own-comment")
            }
            assertThat(lastActivity(bunchId, memberId)).isNull()
        }

        val ownerPermissions = bundleService.get(owner, bunchId, published.id).permissions
        assertThat(ownerPermissions.participant).isFalse()
        assertThat(ownerPermissions.canEdit).isTrue()
        assertThat(ownerPermissions.canDelete).isTrue()
        bundleService.editPublished(actor(owner), bunchId, published.id,
            publishedEdit(published).copy(title = "main owner edit"), "owner-common")
        assertThat(lastActivity(bunchId, owner)).isNull()
        assertBundleError(ErrorCode.BUNDLE_STATE_CONFLICT) {
            bundleService.comment(actor(owner), bunchId, published.id,
                BundleTextRequest("not my comment", 0, BundleStatus.PUBLISHED), "owner-comment")
        }

        inTransaction {
            setRole(bunchId, owner, MembershipRole.MEMBER)
            setRole(bunchId, coOwner, MembershipRole.OWNER)
        }
        assertThat(bundleService.get(owner, bunchId, published.id).permissions.canDelete).isFalse()
        assertBundleError(ErrorCode.FORBIDDEN) {
            bundleService.delete(actor(owner), bunchId, published.id, "former-owner-delete")
        }
        bundleService.delete(actor(coOwner), bunchId, published.id, "current-owner-delete")
        assertThat(bundleRepository.existsById(published.id)).isFalse()
    }

    @Test
    fun `draft managers require current participation and creators retain draft deletion rights`() {
        val owner = member()
        val creator = member()
        val coOwner = member()
        val participant = member()
        val bunchId = bunchWithMembers(owner, creator, coOwner, participant)
        setRole(bunchId, coOwner, MembershipRole.CO_OWNER)
        val source = photo(bunchId, listOf(creator))
        val draft = createDraft(creator, bunchId, listOf(source.id))
        listOf(owner, coOwner).forEach { administrator ->
            val permissions = bundleService.get(administrator, bunchId, draft.id).permissions
            assertThat(permissions.administrator).isTrue()
            assertThat(permissions.canEdit).isFalse()
            assertThat(permissions.canPublish).isFalse()
            assertThat(permissions.canDelete).isFalse()
            assertBundleError(ErrorCode.FORBIDDEN) {
                bundleService.delete(actor(administrator), bunchId, draft.id, "nonparticipant-delete")
            }
            assertBundleError(ErrorCode.FORBIDDEN) {
                bundleService.publish(actor(administrator), bunchId, draft.id, UUID.randomUUID(),
                    PublishBundleRequest(draft.revision))
            }
            assertBundleError(ErrorCode.FORBIDDEN) {
                bundleLeases.acquire(actor(administrator), bunchId, draft.id, "TITLE", "nonparticipant-lease")
            }
        }
        bundleService.addParticipant(actor(creator), bunchId, draft.id, participant, "add-member")
        assertThat(bundleService.get(participant, bunchId, draft.id).permissions.canDelete).isFalse()
        assertBundleError(ErrorCode.FORBIDDEN) {
            bundleService.delete(actor(participant), bunchId, draft.id, "ordinary-participant-delete")
        }
        bundleService.addParticipant(actor(creator), bunchId, draft.id, coOwner, "add-manager")
        val managerPermissions = bundleService.get(coOwner, bunchId, draft.id).permissions
        assertThat(managerPermissions.canEdit).isTrue()
        assertThat(managerPermissions.canPublish).isTrue()
        assertThat(managerPermissions.canDelete).isTrue()
        setRole(bunchId, coOwner, MembershipRole.MEMBER)
        assertThat(bundleService.get(coOwner, bunchId, draft.id).permissions.canDelete).isFalse()
        assertBundleError(ErrorCode.FORBIDDEN) {
            bundleService.delete(actor(coOwner), bunchId, draft.id, "demoted-manager-delete")
        }
        setRole(bunchId, coOwner, MembershipRole.CO_OWNER)
        bundleService.delete(actor(coOwner), bunchId, draft.id, "participating-manager-delete")

        val creatorDraft = createDraft(creator, bunchId, listOf(source.id))
        assertThat(creatorDraft.permissions.canDelete).isTrue()
        bundleService.delete(actor(creator), bunchId, creatorDraft.id, "creator-delete")
        assertThat(bundleRepository.existsById(creatorDraft.id)).isFalse()

        val ready = publishableDraft(creator, coOwner, bunchId, source, "co-owner-publish")
        val published = bundleService.publish(actor(coOwner), bunchId, ready.id, UUID.randomUUID(),
            PublishBundleRequest(ready.revision))
        assertThat(published.status).isEqualTo(BundleStatus.PUBLISHED)
        assertThat(published.permissions.canEdit).isFalse()
        assertThat(published.permissions.canDelete).isFalse()
        assertBundleError(ErrorCode.BUNDLE_STATE_CONFLICT) {
            bundleLeases.acquire(actor(coOwner), bunchId, ready.id, "TITLE", "published-lease")
        }
    }

    @Test
    fun `draft list follows actual content update then id while lease heartbeat and reads preserve order`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val source = photo(bunchId, listOf(owner))
        val first = createDraft(owner, bunchId, listOf(source.id))
        val second = createDraft(owner, bunchId, listOf(source.id))
        val third = createDraft(owner, bunchId, listOf(source.id))
        val baseline = Instant.parse("2026-01-01T00:00:00Z")
        listOf(first, second, third).forEach {
            jdbc.update(
                "UPDATE bundles SET created_at = ?, updated_at = ? WHERE id = ?",
                java.sql.Timestamp.from(baseline),
                java.sql.Timestamp.from(baseline),
                it.id
            )
        }
        fun ordered() = bundleService.list(owner, bunchId, BundleStatus.DRAFT, 0, 2)
        assertThat(ordered().items.map { it.id }).containsExactly(third.id, second.id)
        assertThat(ordered().total).isEqualTo(3)
        val lease = bundleLeases.acquire(actor(owner), bunchId, first.id, "TITLE", "sort-title")
        bundleLeases.heartbeat(actor(owner), bunchId, first.id, "TITLE", lease.token, "sort-title")
        bundleService.get(owner, bunchId, first.id)
        assertThat(ordered().items.map { it.id }).containsExactly(third.id, second.id)
        bundleService.title(actor(owner), bunchId, first.id,
            BundleTextRequest("recent edit", 0, BundleStatus.DRAFT, lease.token), "sort-edit")
        assertThat(ordered().items.map { it.id }).containsExactly(first.id, third.id)
        assertThat(bundleService.list(owner, bunchId, BundleStatus.DRAFT, 1, 2).items.map { it.id })
            .containsExactly(second.id)
    }

    @Test
    fun `activity records only committed content changes and comment saves`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val source = photo(bunchId, listOf(owner))
        val addition = photo(bunchId, listOf(owner))
        val draft = createDraft(owner, bunchId, listOf(source.id))
        assertThat(lastActivity(bunchId, owner)).isNull()
        val baseline = Instant.parse("2026-01-01T00:00:00Z")
        fun resetActivity() {
            jdbc.update("UPDATE bunch_memberships SET last_activity_at = ? WHERE bunch_id = ? AND member_id = ?",
                java.sql.Timestamp.from(baseline), bunchId, owner)
        }
        val titleLease = bundleLeases.acquire(actor(owner), bunchId, draft.id, "TITLE", "activity-title")
        val dateLease = bundleLeases.acquire(actor(owner), bunchId, draft.id, "DATE", "activity-date")
        val photoId = draft.photos.single().id
        val captionLease = bundleLeases.acquire(actor(owner), bunchId, draft.id, "PHOTO:$photoId", "activity-photo")
        val photoKey = UUID.randomUUID()
        val photoRequest = BundlePhotoChangesRequest(listOf(addition.id), mode = BundleStatus.DRAFT)
        val edits = listOf<() -> Any?>(
            { bundleService.title(actor(owner), bunchId, draft.id,
                BundleTextRequest("activity title", 0, BundleStatus.DRAFT, titleLease.token), "activity-title") },
            { bundleService.visibility(actor(owner), bunchId, draft.id,
                BundleVisibilityRequest(Visibility.PRIVATE, 0, BundleStatus.DRAFT), "activity-visibility") },
            { bundleService.date(actor(owner), bunchId, draft.id,
                BundleDateRequest(LocalDate.of(2024, 1, 1), 0, BundleStatus.DRAFT, dateLease.token), "activity-date") },
            { bundleService.caption(actor(owner), bunchId, draft.id, photoId,
                BundleTextRequest("activity caption", 0, BundleStatus.DRAFT, captionLease.token), "activity-caption") },
            { bundleService.changePhotos(actor(owner), bunchId, draft.id, photoKey, photoRequest) },
            { bundleService.move(actor(owner), bunchId, draft.id, photoId,
                MoveBundlePhotoRequest(bundleService.get(owner, bunchId, draft.id).photos.last().id,
                    null, BundleStatus.DRAFT), "activity-move") },
            { bundleService.comment(actor(owner), bunchId, draft.id,
                BundleTextRequest("activity comment", 0, BundleStatus.DRAFT), "activity-comment") }
        )
        edits.forEach { edit ->
            resetActivity()
            edit()
            assertThat(lastActivity(bunchId, owner)).isAfter(baseline)
            assertThat(lastActivity(bunchId, guest)).isNull()
        }
        resetActivity()
        val unchanged = bundleService.get(owner, bunchId, draft.id)
        bundleService.list(owner, bunchId, BundleStatus.DRAFT, 0, 20)
        bundleService.summary(owner, bunchId)
        bundleLeases.heartbeat(actor(owner), bunchId, draft.id, "TITLE", titleLease.token, "activity-title")
        bundleService.title(actor(owner), bunchId, draft.id,
            BundleTextRequest(unchanged.title, unchanged.titleVersion, BundleStatus.DRAFT, titleLease.token), "noop-title")
        bundleService.visibility(actor(owner), bunchId, draft.id,
            BundleVisibilityRequest(requireNotNull(unchanged.visibility), unchanged.visibilityVersion, BundleStatus.DRAFT), "noop-visibility")
        bundleService.comment(actor(owner), bunchId, draft.id,
            BundleTextRequest("activity comment", 1, BundleStatus.DRAFT), "noop-comment")
        bundleService.changePhotos(actor(owner), bunchId, draft.id, photoKey, photoRequest)
        assertBundleError(ErrorCode.BUNDLE_VERSION_CONFLICT) {
            bundleService.title(actor(owner), bunchId, draft.id,
                BundleTextRequest("stale save", 0, BundleStatus.DRAFT, titleLease.token), "stale-title")
        }
        assertThatThrownBy {
            inTransaction {
                bundleService.comment(actor(owner), bunchId, draft.id,
                    BundleTextRequest("rolled back comment", 1, BundleStatus.DRAFT), "rolled-back-comment")
                error("rollback after successful edit")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        bundleService.addParticipant(actor(owner), bunchId, draft.id, guest, "activity-add-member")
        assertThat(lastActivity(bunchId, owner)).isEqualTo(baseline)
        assertThat(bundleService.get(owner, bunchId, draft.id).revision).isEqualTo(unchanged.revision + 1)
    }

    private fun setRole(bunchId: Long, memberId: Long, role: MembershipRole) {
        jdbc.update("UPDATE bunch_memberships SET role = ? WHERE bunch_id = ? AND member_id = ? AND status = 'ACTIVE'",
            role.name, bunchId, memberId)
    }

    private fun lastActivity(bunchId: Long, memberId: Long): Instant? = jdbc.queryForObject(
        "SELECT last_activity_at FROM bunch_memberships WHERE bunch_id = ? AND member_id = ? AND status = 'ACTIVE'",
        { row, _ -> row.getTimestamp(1)?.toInstant() }, bunchId, memberId
    )

    private fun createDraft(owner: Long, bunchId: Long, photoIds: List<Long>): BundleSnapshot =
        bundleService.create(actor(owner), bunchId, UUID.randomUUID(), CreateBundleRequest(photoIds))

    private fun publishableDraft(
        owner: Long,
        guest: Long,
        bunchId: Long,
        source: Photo,
        label: String
    ): BundleSnapshot {
        val draft = createDraft(owner, bunchId, listOf(source.id))
        bundleService.addParticipant(actor(owner), bunchId, draft.id, guest, "$label-add-participant")
        val guestCommentVersion = bundleService.get(owner, bunchId, draft.id)
            .comments.firstOrNull { it.memberId == guest }?.version ?: 0
        val titleGrant = bundleLeases.acquire(
            actor(owner),
            bunchId,
            draft.id,
            "TITLE",
            "$label-title-connection"
        )
        bundleService.title(
            actor(owner),
            bunchId,
            draft.id,
            BundleTextRequest("$label title", 0, BundleStatus.DRAFT, titleGrant.token),
            "$label-title"
        )
        bundleService.visibility(
            actor(owner),
            bunchId,
            draft.id,
            BundleVisibilityRequest(Visibility.PRIVATE, 0, BundleStatus.DRAFT),
            "$label-visibility"
        )
        bundleService.comment(
            actor(owner),
            bunchId,
            draft.id,
            BundleTextRequest("$label owner comment", 0, BundleStatus.DRAFT),
            "$label-owner-comment"
        )
        bundleService.comment(
            actor(guest),
            bunchId,
            draft.id,
            BundleTextRequest("$label guest comment", guestCommentVersion, BundleStatus.DRAFT),
            "$label-guest-comment"
        )
        return bundleService.get(owner, bunchId, draft.id)
    }

    private fun publishReadyBundle(
        owner: Long,
        guest: Long,
        bunchId: Long,
        source: Photo,
        label: String
    ): BundleSnapshot {
        val ready = publishableDraft(owner, guest, bunchId, source, label)
        return bundleService.publish(
            actor(owner),
            bunchId,
            ready.id,
            UUID.randomUUID(),
            PublishBundleRequest(ready.revision)
        )
    }

    private fun publishedEdit(value: BundleSnapshot) = EditPublishedBundleRequest(
        value.revision, value.title, value.visibility!!, value.date,
        value.photos.map { PublishedBundlePhotoInput(bundlePhotoId = it.id, caption = it.caption) },
        emptyList()
    )

    private fun inTransaction(action: () -> Unit) {
        TransactionTemplate(transactionManager).execute<Any?> {
            action()
            null
        }
    }

    private fun assertSummary(bunchId: Long, publishedCount: Long, stage: Int) {
        val summary = bundleService.summary(
            bundleRepository.findAllByBunchIdOrderByIdAsc(bunchId).first().createdBy,
            bunchId
        )
        assertThat(summary.publishedCount).isEqualTo(publishedCount)
        assertThat(summary.stage).isEqualTo(stage)
    }

    private fun bunchWithMembers(vararg memberIds: Long): Long {
        require(memberIds.size >= 2)
        val owner = memberIds.first()
        val bunchId = ownedBunch(create(owner).id)
        memberIds.drop(1).forEach { join(it, issue(owner, bunchId)) }
        return bunchId
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
                publicId = "bundle-test/${UUID.randomUUID()}",
                url = "https://images.example/bundle/${UUID.randomUUID()}.jpg"
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

    private fun actor(memberId: Long, sessionId: String = "session-$memberId") =
        BundleActor(memberId, sessionId)

    private fun assertBundleError(code: ErrorCode, action: () -> Any?) {
        val thrown = org.assertj.core.api.Assertions.catchThrowable {
            action()
            Unit
        }
        assertThat(thrown).isInstanceOf(BusinessException::class.java)
        assertThat((thrown as BusinessException).errorCode).isEqualTo(code)
    }

    private fun errorCode(throwable: Throwable?): ErrorCode? =
        generateSequence(throwable) { it.cause }
            .filterIsInstance<BusinessException>()
            .map { it.errorCode }
            .firstOrNull()
}
