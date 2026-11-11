package com.bunch.bundle

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.domain.Visibility
import com.bunch.bunch.dto.BunchMembershipExitRequest
import com.bunch.bunch.service.BunchMembershipExitService
import com.bunch.bundle.domain.BundlePhotoPickRepository
import com.bunch.bundle.domain.BundlePhotoRepository
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.domain.BundleStatus
import com.bunch.bundle.dto.BundleActor
import com.bunch.bundle.dto.BundlePhotoChangesRequest
import com.bunch.bundle.dto.BundlePhotoPickRequest
import com.bunch.bundle.dto.BundleSnapshot
import com.bunch.bundle.dto.BundleTextRequest
import com.bunch.bundle.dto.BundleVisibilityRequest
import com.bunch.bundle.dto.CreateBundleRequest
import com.bunch.bundle.dto.EditPublishedBundleRequest
import com.bunch.bundle.dto.MoveBundlePhotoRequest
import com.bunch.bundle.dto.PublishedBundlePhotoInput
import com.bunch.bundle.dto.PublishBundleRequest
import com.bunch.bundle.service.BundleLeaseService
import com.bunch.bundle.service.BundlePhotoPickService
import com.bunch.bundle.service.BundleService
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
import com.bunch.photo.service.PhotoService
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class BundlePhotoPickLifecycleIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var bundleService: BundleService

    @Autowired
    private lateinit var bundleLeases: BundleLeaseService

    @Autowired
    private lateinit var pickService: BundlePhotoPickService

    @Autowired
    private lateinit var pickRows: BundlePhotoPickRepository

    @Autowired
    private lateinit var bundlePhotos: BundlePhotoRepository

    @Autowired
    private lateinit var bundleRepository: BundleRepository

    @Autowired
    private lateinit var bunchRepository: BunchRepository

    @Autowired
    private lateinit var assets: ImageAssetRepository

    @Autowired
    private lateinit var photos: PhotoRepository

    @Autowired
    private lateinit var uploaders: PhotoUploaderRepository

    @Autowired
    private lateinit var photoService: PhotoService

    @Autowired
    private lateinit var exits: BunchMembershipExitService

    @Autowired
    private lateinit var memberships: BunchMembershipRepository

    @Autowired
    private lateinit var transactions: PlatformTransactionManager

    @MockitoBean
    private lateinit var storage: ProfileImageStorage

    private val ownedAssetIds = mutableListOf<Long>()

    @AfterEach
    fun cleanBundleReferencesBeforeBunchSupport() {
        jdbc.update("DELETE FROM bundle_photo_picks")
        jdbc.update("DELETE FROM bundle_edit_leases")
        jdbc.update("DELETE FROM bundle_comments")
        jdbc.update("DELETE FROM bundle_participants")
        jdbc.update("DELETE FROM bundle_photos")
        jdbc.update("DELETE FROM bundle_requests")
        jdbc.update("DELETE FROM bundles")
        jdbc.update("DELETE FROM photo_uploaders")
        jdbc.update("DELETE FROM photos")
        ownedAssetIds.forEach { jdbc.update("DELETE FROM image_assets WHERE id = ?", it) }
        ownedAssetIds.clear()
    }

    @Test
    fun `draft picks isolate bundles and preserve participant order through photo reorder`() {
        val owner = member()
        val first = member()
        val second = member()
        val bunchId = bunchWithMembers(owner, first, second)
        val firstSource = photo(bunchId, first)
        val secondSource = photo(bunchId, second)
        val main = createDraft(owner, bunchId, listOf(firstSource, secondSource))
        val isolated = createDraft(owner, bunchId, listOf(firstSource))
        val mainRows = bundlePhotos.findAllByBundleIdOrderByPositionAscIdAsc(main.id)
        val mainFirst = mainRows.first { it.photoId == firstSource.id }
        val mainSecond = mainRows.first { it.photoId == secondSource.id }
        val isolatedRow = bundlePhotos.findAllByBundleIdOrderByPositionAscIdAsc(isolated.id).single()

        assertThat(main.revision).isEqualTo(1)
        assertThat(bundleService.get(owner, bunchId, main.id).participants.map { it.memberId })
            .containsExactly(owner, first, second)

        val firstPick = pick(first, bunchId, main.id, mainFirst.id, true)
        assertThat(firstPick.version).isEqualTo(bundleService.get(owner, bunchId, main.id).revision)
        val secondPick = pick(second, bunchId, main.id, mainFirst.id, true)
        assertThat(secondPick.version).isEqualTo(bundleService.get(owner, bunchId, main.id).revision)
        assertThat(pickRows.findByBundlePhotoIdAndMemberId(mainFirst.id, first)?.version)
            .isEqualTo(firstPick.version)
        assertThat(pickRows.findByBundlePhotoIdAndMemberId(mainFirst.id, second)?.version)
            .isEqualTo(secondPick.version)
        val selected = bundleService.get(owner, bunchId, main.id).photos
            .first { it.id == mainFirst.id }.picks!!
        assertThat(selected.count).isEqualTo(2)
        assertThat(selected.pickedByMe).isFalse()
        assertThat(selected.members.map { it.memberId }).containsExactly(first, second)
        assertThat(selected.members).allMatch { it.profileAvailable && !it.nickname.isNullOrBlank() }

        val isolatedBefore = bundleService.get(owner, bunchId, isolated.id).photos.single().picks!!
        assertThat(isolatedBefore.count).isZero()
        assertThat(isolatedBefore.version).isZero()
        pick(first, bunchId, isolated.id, isolatedRow.id, true)
        assertThat(bundleService.get(owner, bunchId, isolated.id).photos.single().picks!!.count).isEqualTo(1)
        assertThat(bundleService.get(owner, bunchId, main.id).photos.first { it.id == mainFirst.id }.picks!!.count)
            .isEqualTo(2)

        val reordered = bundleService.move(
            actor(owner),
            bunchId,
            main.id,
            mainSecond.id,
            MoveBundlePhotoRequest(
                nextBundlePhotoId = mainFirst.id,
                mode = BundleStatus.DRAFT
            ),
            "pick-reorder"
        )
        assertThat(reordered.photos.map { it.photoId }).containsExactly(secondSource.id, firstSource.id)
        assertThat(reordered.photos.first().picks!!.count).isZero()
        assertThat(reordered.photos.last().picks!!.count).isEqualTo(2)
        assertThat(reordered.photos.last().picks!!.members.map { it.memberId }).containsExactly(first, second)
    }

    @Test
    fun `cached first pick versions are independent of other photos members and draft edits`() {
        val owner = member()
        val guest = member()
        val selected = member()
        val bunchId = bunchWithMembers(owner, guest, selected)
        val draft = createDraft(owner, bunchId, listOf(photo(bunchId, owner), photo(bunchId, owner)), listOf(selected))
        val added = bundleService.addParticipant(actor(owner), bunchId, draft.id, guest, "initial-pick-guest")
        val ownerBefore = bundleService.get(owner, bunchId, draft.id)
        val guestBefore = bundleService.get(guest, bunchId, draft.id)
        assertThat(ownerBefore.photos.map { it.picks!!.version }).containsOnly(0L)
        assertThat(guestBefore.photos.map { it.picks!!.version }).containsOnly(added.revision)

        bundleService.visibility(
            actor(owner), bunchId, draft.id,
            BundleVisibilityRequest(Visibility.PUBLIC, added.visibilityVersion, BundleStatus.DRAFT),
            "unrelated-scope"
        )
        assertThat(bundleService.get(owner, bunchId, draft.id).photos.map { it.picks })
            .isEqualTo(ownerBefore.photos.map { it.picks })
        assertThat(bundleService.get(guest, bunchId, draft.id).photos.map { it.picks })
            .isEqualTo(guestBefore.photos.map { it.picks })

        for ((memberId, snapshot) in listOf(owner to ownerBefore, guest to guestBefore)) {
            for (photo in snapshot.photos) {
                val saved = pickService.save(
                    memberId, bunchId, draft.id, photo.id, BundlePhotoPickRequest(true, photo.picks!!.version)
                )
                assertThat(saved.pickedByMe).isTrue()
            }
        }
        assertThat(bundleService.get(owner, bunchId, draft.id).photos.map { it.picks!!.count }).containsOnly(2)
    }

    @Test
    fun `removing and readding a never picked participant invalidates cached initial versions`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val draft = createDraft(owner, bunchId, listOf(photo(bunchId, owner)))
        bundleService.addParticipant(actor(owner), bunchId, draft.id, guest, "initial-add")
        val old = bundleService.get(guest, bunchId, draft.id).photos.single()
        bundleService.removeParticipant(actor(owner), bunchId, draft.id, guest, "initial-remove")
        val readded = bundleService.addParticipant(actor(owner), bunchId, draft.id, guest, "initial-readd")
        val fresh = bundleService.get(guest, bunchId, draft.id).photos.single()
        assertThat(fresh.picks!!.version).isEqualTo(readded.revision).isGreaterThan(old.picks!!.version)
        assertError(ErrorCode.BUNDLE_VERSION_CONFLICT) {
            pickService.save(guest, bunchId, draft.id, old.id, BundlePhotoPickRequest(true, old.picks!!.version))
        }
        assertThat(pickRows.findAllByBundleId(draft.id)).isEmpty()
        assertThat(pickService.save(
            guest, bunchId, draft.id, fresh.id, BundlePhotoPickRequest(true, fresh.picks!!.version)
        ).pickedByMe).isTrue()
    }

    @Test
    fun `draft stale desired retries are no-op but rejoin resets the pick version`() {
        val owner = member()
        val participant = member()
        val bunchId = bunchWithMembers(owner, participant)
        val source = photo(bunchId, owner)
        val draft = createDraft(owner, bunchId, listOf(source))
        bundleService.addParticipant(actor(owner), bunchId, draft.id, participant, "pick-add")
        val row = bundlePhotos.findAllByBundleIdOrderByPositionAscIdAsc(draft.id).single()

        val participantPick = pick(participant, bunchId, draft.id, row.id, true)
        pick(owner, bunchId, draft.id, row.id, true)
        val revisionAfterOtherMember = bundleService.get(owner, bunchId, draft.id).revision
        val staleDesiredRetry = pickService.save(
            participant,
            bunchId,
            draft.id,
            row.id,
            BundlePhotoPickRequest(true, participantPick.version)
        )
        assertThat(staleDesiredRetry.pickedByMe).isTrue()
        assertThat(staleDesiredRetry.version).isEqualTo(participantPick.version)
        assertThat(bundleService.get(owner, bunchId, draft.id).revision).isEqualTo(revisionAfterOtherMember)

        bundleService.removeParticipant(actor(owner), bunchId, draft.id, participant, "pick-remove")
        assertThat(pickRows.findByBundlePhotoIdAndMemberId(row.id, participant)).isNull()
        bundleService.addParticipant(actor(owner), bunchId, draft.id, participant, "pick-readd")

        val reset = bundleService.get(participant, bunchId, draft.id).photos.single().picks!!
        assertThat(reset.pickedByMe).isFalse()
        assertThat(reset.version).isEqualTo(bundleService.get(owner, bunchId, draft.id).revision)
        assertError(ErrorCode.BUNDLE_VERSION_CONFLICT) {
            pickService.save(
                participant,
                bunchId,
                draft.id,
                row.id,
                BundlePhotoPickRequest(true, participantPick.version)
            )
        }

        val fresh = pickService.save(
            participant,
            bunchId,
            draft.id,
            row.id,
            BundlePhotoPickRequest(true, reset.version)
        )
        assertThat(fresh.count).isEqualTo(2)
        assertThat(fresh.pickedByMe).isTrue()
        assertThat(fresh.version).isEqualTo(bundleService.get(owner, bunchId, draft.id).revision)
        assertThat(pickRows.findByBundlePhotoIdAndMemberId(row.id, participant)?.version)
            .isEqualTo(fresh.version)
    }

    @Test
    fun `actual draft membership exits remove relevant picks and creator exit removes all drafts`() {
        val owner = member()
        val participant = member()
        val remaining = member()
        val bunchId = bunchWithMembers(owner, participant, remaining)
        val firstDraft = createDraft(owner, bunchId, listOf(photo(bunchId, owner)))
        val secondDraft = createDraft(owner, bunchId, listOf(photo(bunchId, owner)))
        listOf(firstDraft, secondDraft).forEachIndexed { index, draft ->
            bundleService.addParticipant(actor(owner), bunchId, draft.id, participant, "exit-add-$index")
            val row = bundlePhotos.findAllByBundleIdOrderByPositionAscIdAsc(draft.id).single()
            pick(owner, bunchId, draft.id, row.id, true)
            pick(participant, bunchId, draft.id, row.id, true)
        }

        val participantMembership = memberships.findByMemberIdAndBunchIdAndStatus(participant, bunchId)!!
        exits.leave(
            participant,
            bunchId,
            UUID.randomUUID(),
            BunchMembershipExitRequest(participantMembership.id, bunchVersion(bunchId))
        )

        listOf(firstDraft, secondDraft).forEach { draft ->
            val afterParticipantExit = bundleService.get(owner, bunchId, draft.id)
            val row = afterParticipantExit.photos.single()
            val state = row.picks!!
            assertThat(afterParticipantExit.participants.map { it.memberId }).containsExactly(owner)
            assertThat(state.count).isEqualTo(1)
            assertThat(state.pickedByMe).isTrue()
            assertThat(state.members.map { it.memberId }).containsExactly(owner)
            assertThat(pickRows.findByBundlePhotoIdAndMemberId(row.id, participant)).isNull()
            assertThat(pickRows.findByBundlePhotoIdAndMemberId(row.id, owner)?.picked).isTrue()
        }

        val ownerMembership = memberships.findByMemberIdAndBunchIdAndStatus(owner, bunchId)!!
        val successorMembership = memberships.findByMemberIdAndBunchIdAndStatus(remaining, bunchId)!!
        exits.leave(
            owner,
            bunchId,
            UUID.randomUUID(),
            BunchMembershipExitRequest(ownerMembership.id, bunchVersion(bunchId), successorMembership.id)
        )

        assertThat(bundleRepository.existsById(firstDraft.id)).isFalse()
        assertThat(bundleRepository.existsById(secondDraft.id)).isFalse()
        assertThat(pickRows.findAll()).isEmpty()
    }

    @Test
    fun `transaction rollback restores a pick and lifecycle participation cleanup together`() {
        val owner = member()
        val participant = member()
        val bunchId = bunchWithMembers(owner, participant)
        val source = photo(bunchId, owner)
        val draft = createDraft(owner, bunchId, listOf(source))
        bundleService.addParticipant(actor(owner), bunchId, draft.id, participant, "rollback-add")
        val before = bundleService.get(owner, bunchId, draft.id)
        val row = before.photos.single()
        val expectedVersion = row.picks!!.version

        assertThatThrownBy {
            TransactionTemplate(transactions).execute<Any?> {
                pickService.save(
                    participant,
                    bunchId,
                    draft.id,
                    row.id,
                    BundlePhotoPickRequest(true, expectedVersion)
                )
                bundleService.memberDeparted(bunchId, participant)
                throw IllegalStateException("rollback lifecycle pick")
            }
        }.isInstanceOf(IllegalStateException::class.java)

        val after = bundleService.get(owner, bunchId, draft.id)
        assertThat(after.revision).isEqualTo(before.revision)
        assertThat(after.participants.map { it.memberId }).containsExactly(owner, participant)
        assertThat(after.photos.single().picks!!.count).isZero()
        assertThat(pickRows.findAllByBundleId(draft.id)).isEmpty()
        assertThat(memberships.findByMemberIdAndBunchIdAndStatus(participant, bunchId)).isNotNull
    }

    @Test
    fun `draft photo removal cascades only the removed pick and rolls back selected rows atomically`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val firstSource = photo(bunchId, owner)
        val secondSource = photo(bunchId, owner)
        val draft = createDraft(owner, bunchId, listOf(firstSource, secondSource))
        val rows = bundlePhotos.findAllByBundleIdOrderByPositionAscIdAsc(draft.id)
        val firstRow = rows.first { it.photoId == firstSource.id }
        val secondRow = rows.first { it.photoId == secondSource.id }
        pick(owner, bunchId, draft.id, firstRow.id, true)
        pick(owner, bunchId, draft.id, secondRow.id, true)
        val before = bundleService.get(owner, bunchId, draft.id)

        assertThatThrownBy {
            TransactionTemplate(transactions).execute<Any?> {
                bundleService.changePhotos(
                    actor(owner),
                    bunchId,
                    draft.id,
                    UUID.randomUUID(),
                    BundlePhotoChangesRequest(
                        removeBundlePhotoIds = listOf(firstRow.id),
                        mode = BundleStatus.DRAFT
                    )
                )
                throw IllegalStateException("rollback selected photo removal")
            }
        }.isInstanceOf(IllegalStateException::class.java)

        val restored = bundleService.get(owner, bunchId, draft.id)
        assertThat(restored.revision).isEqualTo(before.revision)
        assertThat(restored.photos.map { it.photoId }).containsExactly(firstSource.id, secondSource.id)
        assertThat(restored.photos.map { it.picks!!.count }).containsExactly(1, 1)
        assertThat(pickRows.findByBundlePhotoIdAndMemberId(firstRow.id, owner)?.picked).isTrue()

        val removed = bundleService.changePhotos(
            actor(owner),
            bunchId,
            draft.id,
            UUID.randomUUID(),
            BundlePhotoChangesRequest(
                removeBundlePhotoIds = listOf(firstRow.id),
                mode = BundleStatus.DRAFT
            )
        )
        assertThat(removed.photos.map { it.photoId }).containsExactly(secondSource.id)
        assertThat(removed.photos.single().picks!!.count).isEqualTo(1)
        assertThat(pickRows.findByBundlePhotoIdAndMemberId(firstRow.id, owner)).isNull()
        assertThat(pickRows.findByBundlePhotoIdAndMemberId(secondRow.id, owner)?.picked).isTrue()
        assertError(ErrorCode.RESOURCE_NOT_FOUND) {
            pickService.save(
                owner,
                bunchId,
                draft.id,
                firstRow.id,
                BundlePhotoPickRequest(true, 0)
            )
        }
    }

    @Test
    fun `final source deletion removes a draft and cascades its picks`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val source = photo(bunchId, owner)
        val draft = createDraft(owner, bunchId, listOf(source))
        bundleService.addParticipant(actor(owner), bunchId, draft.id, guest, "delete-add")
        val row = bundlePhotos.findAllByBundleIdOrderByPositionAscIdAsc(draft.id).single()
        pick(owner, bunchId, draft.id, row.id, true)
        pick(guest, bunchId, draft.id, row.id, true)

        val result = photoService.removeUploader(bunchId, source.id, owner)

        assertThat(result.photoDeleted).isTrue()
        assertThat(photos.existsById(source.id)).isFalse()
        assertThat(bundleRepository.existsById(draft.id)).isFalse()
        assertThat(bundlePhotos.findAllByBundleIdOrderByPositionAscIdAsc(draft.id)).isEmpty()
        assertThat(pickRows.findAllByBundleId(draft.id)).isEmpty()
        assertThat(assets.existsById(source.imageAsset.id)).isFalse()
    }

    @Test
    fun `published picks are immutable for owner and participant and survive source deletion`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val source = photo(bunchId, owner)
        val ready = preparePublishableDraft(owner, guest, bunchId, listOf(source), "published-freeze")
        val row = ready.photos.single()
        pick(owner, bunchId, ready.id, row.id, true)
        pick(guest, bunchId, ready.id, row.id, true)
        val published = publish(owner, bunchId, ready.id)
        val ownerView = bundleService.get(owner, bunchId, published.id)
        val guestView = bundleService.get(guest, bunchId, published.id)
        assertThat(ownerView.status).isEqualTo(BundleStatus.PUBLISHED)
        assertThat(ownerView.photos.single().picks!!.count).isEqualTo(2)
        assertThat(ownerView.photos.single().picks!!.members.map { it.memberId }).containsExactly(owner, guest)
        assertThat(guestView.photos.single().picks!!.pickedByMe).isTrue()

        assertError(ErrorCode.BUNDLE_STATE_CONFLICT) {
            pickService.save(
                owner,
                bunchId,
                published.id,
                row.id,
                BundlePhotoPickRequest(false, ownerView.photos.single().picks!!.version)
            )
        }
        assertError(ErrorCode.BUNDLE_STATE_CONFLICT) {
            pickService.save(
                guest,
                bunchId,
                published.id,
                row.id,
                BundlePhotoPickRequest(false, guestView.photos.single().picks!!.version)
            )
        }

        val assetId = source.imageAsset.id
        val deletion = photoService.removeUploader(bunchId, source.id, owner)
        assertThat(deletion.photoDeleted).isTrue()
        assertThat(photos.existsById(source.id)).isFalse()
        val retained = bundlePhotos.findById(row.id).orElseThrow()
        assertThat(retained.photoId).isNull()
        assertThat(assets.existsById(assetId)).isTrue()
        assertThat(bundleService.get(owner, bunchId, published.id).photos.single().picks!!.count).isEqualTo(2)
        assertThat(bundleService.get(guest, bunchId, published.id).photos.single().picks!!.pickedByMe).isTrue()
    }

    @Test
    fun `published empty pick summaries retain initial versions through content edits and source deletion`() {
        val owner = member()
        val guest = member()
        val observer = member()
        val bunchId = bunchWithMembers(owner, guest, observer)
        val source = photo(bunchId, owner)
        val ready = preparePublishableDraft(owner, guest, bunchId, listOf(source), "empty-pick-freeze")
        val ownerInitial = ready.photos.single().picks!!
        val guestInitial = bundleService.get(guest, bunchId, ready.id).photos.single().picks!!
        val published = publish(owner, bunchId, ready.id)
        assertThat(published.photos.single().picks).isEqualTo(ownerInitial)
        assertThat(bundleService.get(guest, bunchId, ready.id).photos.single().picks).isEqualTo(guestInitial)
        val observerInitial = bundleService.get(observer, bunchId, ready.id).photos.single().picks!!
        assertThat(observerInitial.version).isZero()

        val saved = bundleService.editPublished(
            actor(owner), bunchId, published.id,
            EditPublishedBundleRequest(
                expectedRevision = published.revision,
                title = "edited without picks",
                date = published.date,
                photos = listOf(PublishedBundlePhotoInput(bundlePhotoId = published.photos.single().id)),
                locations = emptyList()
            ), "empty-pick-edit"
        )
        assertThat(saved.revision).isGreaterThan(published.revision)
        assertThat(saved.photos.single().picks).isEqualTo(ownerInitial)
        assertThat(photoService.removeUploader(bunchId, source.id, owner).photoDeleted).isTrue()
        for ((memberId, initial) in listOf(owner to ownerInitial, guest to guestInitial, observer to observerInitial)) {
            val retained = bundleService.get(memberId, bunchId, published.id).photos.single()
            assertThat(retained.photoId).isNull()
            assertThat(retained.picks).isEqualTo(initial)
        }
    }

    @Test
    fun `published edit cascades removed picks and preserves retained picks`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val removedSource = photo(bunchId, owner)
        val retainedSource = photo(bunchId, owner)
        val addedSource = photo(bunchId, owner)
        val ready = preparePublishableDraft(
            owner,
            guest,
            bunchId,
            listOf(removedSource, retainedSource),
            "published-edit-picks"
        )
        val readyRows = bundlePhotos.findAllByBundleIdOrderByPositionAscIdAsc(ready.id)
        val removed = readyRows.first { it.photoId == removedSource.id }
        val retained = readyRows.first { it.photoId == retainedSource.id }
        pick(owner, bunchId, ready.id, removed.id, true)
        pick(guest, bunchId, ready.id, removed.id, true)
        pick(owner, bunchId, ready.id, retained.id, true)
        pick(guest, bunchId, ready.id, retained.id, true)
        val published = publish(owner, bunchId, ready.id)
        val current = bundleService.get(owner, bunchId, published.id)

        val edited = bundleService.editPublished(
            actor(owner),
            bunchId,
            published.id,
            EditPublishedBundleRequest(
                expectedRevision = current.revision,
                title = current.title,
                date = current.date,
                photos = listOf(
                    PublishedBundlePhotoInput(bundlePhotoId = retained.id),
                    PublishedBundlePhotoInput(photoId = addedSource.id)
                ),
                locations = emptyList()
            ),
            "published-edit-picks"
        )

        assertThat(bundlePhotos.existsById(removed.id)).isFalse()
        assertThat(pickRows.findAllByBundleId(published.id)).allMatch { it.bundlePhotoId == retained.id }
        assertThat(pickRows.findAllByBundleId(published.id)).hasSize(2)
        val retainedAfter = edited.photos.first { it.id == retained.id }.picks!!
        val addedAfter = edited.photos.first { it.photoId == addedSource.id }.picks!!
        assertThat(retainedAfter.count).isEqualTo(2)
        assertThat(retainedAfter.pickedByMe).isTrue()
        assertThat(retainedAfter.members.map { it.memberId }).containsExactly(owner, guest)
        assertThat(addedAfter.count).isZero()
        assertThat(addedAfter.pickedByMe).isFalse()
    }

    @Test
    fun `published attribution greys a departed account and restores only the same active account`() {
        val owner = member()
        val guest = member()
        val bunchId = bunchWithMembers(owner, guest)
        val source = photo(bunchId, owner)
        val ready = preparePublishableDraft(owner, guest, bunchId, listOf(source), "published-attribution")
        val row = ready.photos.single()
        pick(owner, bunchId, ready.id, row.id, true)
        pick(guest, bunchId, ready.id, row.id, true)
        val published = publish(owner, bunchId, ready.id)
        val before = bundleService.get(owner, bunchId, published.id)

        val guestMembership = memberships.findByMemberIdAndBunchIdAndStatus(guest, bunchId)!!
        exits.leave(
            guest,
            bunchId,
            UUID.randomUUID(),
            BunchMembershipExitRequest(guestMembership.id, bunchVersion(bunchId))
        )
        val masked = bundleService.get(owner, bunchId, published.id)
        val maskedGuest = masked.participants.first { it.memberId == guest }
        val maskedPickGuest = masked.photos.single().picks!!.members.first { it.memberId == guest }
        assertThat(masked.participants.map { it.memberId }).containsExactly(owner, guest)
        assertThat(maskedGuest.nickname).isEqualTo("작성자")
        assertThat(maskedGuest.profileAvailable).isFalse()
        assertThat(maskedPickGuest.nickname).isEqualTo("작성자")
        assertThat(maskedPickGuest.profileAvailable).isFalse()

        join(guest, issue(owner, bunchId))
        val restored = bundleService.get(owner, bunchId, published.id)
        val restoredGuest = restored.participants.first { it.memberId == guest }
        assertThat(restored.participants).isEqualTo(before.participants)
        assertThat(restoredGuest.profileAvailable).isTrue()
        assertThat(restoredGuest.nickname).isEqualTo(members.findById(guest).orElseThrow().nickname)

        jdbc.update("UPDATE members SET account_status = 'WITHDRAWN' WHERE id = ?", guest)
        val withdrawn = bundleService.get(owner, bunchId, published.id)
        assertThat(withdrawn.participants.first { it.memberId == guest }.profileAvailable).isFalse()
        assertThat(withdrawn.photos.single().picks!!.members.first { it.memberId == guest }.profileAvailable)
            .isFalse()

        val replacement = member()
        join(replacement, issue(owner, bunchId))
        val newAccount = bundleService.get(owner, bunchId, published.id)
        assertThat(newAccount.participants.map { it.memberId }).containsExactly(owner, guest)
        assertThat(newAccount.participants.map { it.memberId }).doesNotContain(replacement)
        assertThat(newAccount.photos.single().picks!!.members.map { it.memberId }).containsExactly(owner, guest)
        assertThat(newAccount.photos.single().picks!!.members.first { it.memberId == guest }.profileAvailable)
            .isFalse()
    }

    private fun createDraft(
        owner: Long,
        bunchId: Long,
        sources: List<Photo>,
        selectedMembers: List<Long> = uploaders.findAllByPhotoIdInOrderByPhotoIdAndIdAsc(sources.map { it.id })
            .map { it.member.id }.distinct().filter { it != owner }.ifEmpty {
                listOf(memberships.findAllByBunchIdAndStatus(bunchId).first { it.member.id != owner }.member.id)
            }
    ): BundleSnapshot =
        bundleService.create(
            actor(owner),
            bunchId,
            UUID.randomUUID(),
            CreateBundleRequest(sources.map { it.id }, participantMemberIds = selectedMembers)
        )

    private fun preparePublishableDraft(
        owner: Long,
        guest: Long,
        bunchId: Long,
        sources: List<Photo>,
        label: String
    ): BundleSnapshot {
        val draft = createDraft(owner, bunchId, sources, listOf(guest))
        bundleService.addParticipant(actor(owner), bunchId, draft.id, guest, "$label-add-participant")
        val guestCommentVersion = bundleService.get(owner, bunchId, draft.id)
            .comments.firstOrNull { it.memberId == guest }?.version ?: 0L
        val titleToken = bundleLeases.acquire(
            actor(owner),
            bunchId,
            draft.id,
            "TITLE",
            "$label-title-connection"
        ).token
        bundleService.title(
            actor(owner),
            bunchId,
            draft.id,
            BundleTextRequest("$label title", 0, BundleStatus.DRAFT, titleToken),
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

    private fun publish(owner: Long, bunchId: Long, bundleId: Long): BundleSnapshot {
        val ready = bundleService.get(owner, bunchId, bundleId)
        return bundleService.publish(
            actor(owner),
            bunchId,
            bundleId,
            UUID.randomUUID(),
            PublishBundleRequest(ready.revision)
        )
    }

    private fun pick(
        memberId: Long,
        bunchId: Long,
        bundleId: Long,
        bundlePhotoId: Long,
        picked: Boolean
    ) = pickService.save(
        memberId,
        bunchId,
        bundleId,
        bundlePhotoId,
        BundlePhotoPickRequest(
            picked,
            bundleService.get(memberId, bunchId, bundleId).photos
                .first { it.id == bundlePhotoId }.picks!!.version
        )
    )

    private fun bunchWithMembers(vararg memberIds: Long): Long {
        val owner = memberIds.first()
        val bunchId = create(owner).id
        memberIds.drop(1).forEach { join(it, issue(owner, bunchId)) }
        return bunchId
    }

    private fun photo(bunchId: Long, vararg uploaderIds: Long): Photo {
        val asset = assets.saveAndFlush(
            ImageAsset(
                publicId = "bundle-pick-lifecycle/${UUID.randomUUID()}",
                url = "https://images.example/bundle-pick-lifecycle/${UUID.randomUUID()}.jpg"
            )
        )
        ownedAssetIds += asset.id
        val saved = photos.saveAndFlush(
            Photo(
                bunch = bunchRepository.findById(bunchId).orElseThrow(),
                imageAsset = asset,
                fileHash = UUID.randomUUID().toString().replace("-", "").repeat(2),
                processingStatus = ProcessingStatus.READY,
                capturedDate = LocalDate.of(2026, 1, 1),
                capturedTime = LocalTime.NOON,
                dateSource = DateSource.USER,
                createdAt = Instant.now()
            )
        )
        val uploadBatchId = UUID.randomUUID()
        uploaderIds.forEach { uploaderId ->
            uploaders.saveAndFlush(
                PhotoUploader(
                    photo = saved,
                    member = members.findById(uploaderId).orElseThrow(),
                    uploadBatchId = uploadBatchId,
                    uploadedAt = saved.createdAt
                )
            )
        }
        return saved
    }

    private fun actor(memberId: Long, sessionId: String = "pick-lifecycle-$memberId") =
        BundleActor(memberId, sessionId)

    private fun bunchVersion(bunchId: Long): Long = jdbc.queryForObject(
        "SELECT membership_version FROM bunches WHERE id = ?",
        Long::class.java,
        bunchId
    )!!
}
