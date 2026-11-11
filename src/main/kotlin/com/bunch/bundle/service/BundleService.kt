package com.bunch.bundle.service

import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bundle.domain.*
import com.bunch.bundle.dto.*
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.image.service.ImageAssetService
import com.bunch.feed.service.FeedAccessService
import com.bunch.social.dto.SocialLikeSummary
import com.bunch.social.service.SocialLikeService
import com.bunch.social.service.SocialCommentService
import com.bunch.photo.domain.Photo
import com.bunch.photo.domain.PhotoRepository
import com.bunch.photo.domain.PhotoUploaderRepository
import com.bunch.photo.service.PhotoService
import com.bunch.notification.service.NotificationWriter
import com.bunch.notification.service.PushJobWriter
import com.bunch.recommendation.service.RecommendationService
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Service
class BundleService(
    private val access: BundleAccessService,
    private val feedAccess: FeedAccessService,
    private val attribution: BundleAttributionService,
    private val likes: SocialLikeService,
    private val socialComments: SocialCommentService,
    private val bunches: BunchRepository,
    private val bundles: BundleRepository,
    private val photos: BundlePhotoRepository,
    private val participants: BundleParticipantRepository,
    private val comments: BundleCommentRepository,
    private val requests: BundleRequestRepository,
    private val memberships: BunchMembershipRepository,
    private val photoService: PhotoService,
    private val originalPhotos: PhotoRepository,
    private val uploaders: PhotoUploaderRepository,
    private val leases: BundleLeaseService,
    private val locations: BundleLocationService,
    private val images: ImageAssetService,
    private val events: ApplicationEventPublisher,
    private val mapper: ObjectMapper,
    private val stagePolicy: BundleStagePolicy,
    private val titleCrdt: TitleCrdtService,
    private val photoOrder: BundlePhotoOrderService,
    private val recommendations: RecommendationService,
    private val notifications: NotificationWriter,
    private val pushJobs: PushJobWriter,
    private val clock: Clock
) {
    @Transactional
    fun create(actor: BundleActor, bunchId: Long, key: UUID, request: CreateBundleRequest): BundleSnapshot {
        access.lockBunch(actor.memberId, bunchId)
        val fingerprint = fingerprint(request)
        replay(actor.memberId, bunchId, "CREATE", key, fingerprint)?.let { return snapshot(it, actor.memberId) }
        if (memberships.countByBunchIdAndStatus(bunchId) < 2) fail(ErrorCode.BUNDLE_LIMIT_EXCEEDED)
        if (bundles.countByBunchIdAndStatus(bunchId, BundleStatus.DRAFT) >= 10) {
            fail(ErrorCode.BUNDLE_LIMIT_EXCEEDED)
        }
        if (request.photoIds.size !in 1..20) fail(ErrorCode.INVALID_INPUT)
        val selected = photoService.lockReadyPhotosForConnection(bunchId, request.photoIds)
        val recommended = request.recommendationId?.let { recommendations.useCandidate(bunchId, it) }
        val bundle = bundles.saveAndFlush(
            Bundle(
                bunchId = bunchId,
                createdBy = actor.memberId,
                title = recommended?.title.orEmpty(),
                recommendedDate = recommended?.startDate,
                createdAt = clock.instant()
            )
        )
        bundle.titleCrdt = titleCrdt.initialState(bundle.title)
        selected.forEachIndexed { index, photo -> addPhoto(bundle, photo, index, actor.memberId) }
        val currentMembers = memberships.findAllByBunchIdAndStatus(bunchId).map { it.member.id }.toSet()
        val initial = linkedSetOf(actor.memberId)
        uploaders.findAllByPhotoIdInOrderByPhotoIdAndIdAsc(request.photoIds)
            .map { it.member.id }.filter { it in currentMembers }.forEach(initial::add)
        initial.forEach {
            val participant = participants.saveAndFlush(
                BundleParticipant(bundleId = bundle.id, memberId = it, joinedAt = bundle.createdAt)
            )
            notifications.participantAdded(bundle, participant, actor.memberId)
        }
        changed(bundle, key.toString(), BundleEventType.CREATED, content(bundle))
        remember(actor.memberId, bundle, "CREATE", key, fingerprint)
        return snapshot(bundle, actor.memberId)
    }

    @Transactional
    fun get(userId: Long, bunchId: Long, bundleId: Long): BundleSnapshot =
        snapshot(access.lock(userId, bunchId, bundleId), userId)

    @Transactional
    fun list(userId: Long, bunchId: Long, status: BundleStatus, page: Int, size: Int): BundlePage {
        access.lockBunch(userId, bunchId)
        if (page < 0 || size !in 1..50) fail(ErrorCode.INVALID_INPUT)
        val sort = Sort.by(Sort.Direction.DESC, if (status == BundleStatus.DRAFT) "updatedAt" else "publishedAt", "id")
        val pageable = PageRequest.of(page, size, sort)
        val result = if (status == BundleStatus.PUBLISHED) feedAccess.publishedBundles(userId, bunchId, pageable)
            else bundles.findAllByBunchIdAndStatus(bunchId, status, pageable)
        val ids = result.content.filter { it.status == BundleStatus.PUBLISHED }.map { it.id }
        val likeTotals = likes.summaries(userId, ids)
        val commentTotals = socialComments.counts(userId, ids)
        return BundlePage(result.content.map {
            snapshot(it, userId, likeTotals[it.id] ?: SocialLikeSummary(), commentTotals[it.id] ?: 0)
        }, page, size, result.totalElements)
    }

    @Transactional(readOnly = true)
    fun summary(userId: Long, bunchId: Long): BundleSummary {
        access.requireMember(userId, bunchId)
        val published = bundles.countByBunchIdAndStatus(bunchId, BundleStatus.PUBLISHED)
        return BundleSummary(
            bundles.countByBunchIdAndStatus(bunchId, BundleStatus.DRAFT), published, stagePolicy.stage(published)
        )
    }

    @Transactional
    fun title(
        actor: BundleActor,
        bunchId: Long,
        id: Long,
        request: BundleTextRequest,
        requestId: String
    ): BundleSnapshot {
        val bundle = editable(actor, bunchId, id, request.mode)
        version(bundle.titleVersion, request.expectedVersion, mapOf("value" to bundle.title))
        val value = validateText { BundleText.title(request.value) }
        if (bundle.title != value) {
            val rebased = titleCrdt.rebase(bundle.titleCrdt, value)
            bundle.title = rebased.value
            bundle.titleCrdt = rebased.state
            bundle.titleVersion++
            changed(bundle, requestId, BundleEventType.TITLE, titleChanges(bundle))
            recordActivity(bundle, actor.memberId)
            notifyReady(bundle, actor.memberId)
        }
        return snapshot(bundle, actor.memberId)
    }

    @Transactional
    fun mergeTitle(
        actor: BundleActor,
        bunchId: Long,
        id: Long,
        incomingState: ByteArray,
        requestId: String
    ): BundleTitleState {
        val bundle = editable(actor, bunchId, id, BundleStatus.DRAFT)
        val current = bundle.titleCrdt ?: titleCrdt.initialState(bundle.title)
        val merged = titleCrdt.merge(current, incomingState)
        if (!current.contentEquals(merged.state)) {
            bundle.title = merged.value
            bundle.titleCrdt = merged.state
            bundle.titleVersion++
            changed(bundle, requestId, BundleEventType.TITLE, titleChanges(bundle))
            recordActivity(bundle, actor.memberId)
            notifyReady(bundle, actor.memberId)
        }
        return BundleTitleState(bundle.title, bundle.titleCrdt ?: current, bundle.titleVersion,
            BundleText.titleTooLong(bundle.title))
    }

    private fun titleChanges(bundle: Bundle): Map<String, Any?> = mapOf(
        "value" to bundle.title,
        "version" to bundle.titleVersion,
        "title" to bundle.title,
        "titleVersion" to bundle.titleVersion,
        "titleCrdtState" to if (bundle.status == BundleStatus.DRAFT) {
            bundle.titleCrdt ?: titleCrdt.initialState(bundle.title)
        } else null,
        "titleTooLong" to BundleText.titleTooLong(bundle.title)
    )

    @Transactional
    fun visibility(
        actor: BundleActor,
        bunchId: Long,
        id: Long,
        request: BundleVisibilityRequest,
        requestId: String
    ): BundleSnapshot {
        val bundle = editable(actor, bunchId, id, request.mode)
        version(bundle.visibilityVersion, request.expectedVersion, mapOf("value" to bundle.visibility))
        if (bundle.visibility != request.value) {
            bundle.visibility = request.value
            bundle.visibilityVersion++
            changed(bundle, requestId, BundleEventType.VISIBILITY, mapOf(
                "value" to request.value,
                "version" to bundle.visibilityVersion
            ))
            recordActivity(bundle, actor.memberId)
            notifyReady(bundle, actor.memberId)
        }
        return snapshot(bundle, actor.memberId)
    }

    @Transactional
    fun date(
        actor: BundleActor,
        bunchId: Long,
        id: Long,
        request: BundleDateRequest,
        requestId: String
    ): BundleSnapshot {
        val bundle = editable(actor, bunchId, id, request.mode)
        if (request.value?.isAfter(LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Seoul"))) == true) fail(ErrorCode.INVALID_INPUT)
        if (bundle.date != request.value) {
            bundle.date = request.value
            bundle.dateVersion++
            changed(bundle, requestId, BundleEventType.DATE, mapOf(
                "value" to bundle.date, "version" to bundle.dateVersion, "dateChips" to dateChips(bundle)
            ))
            recordActivity(bundle, actor.memberId)
        }
        return snapshot(bundle, actor.memberId)
    }

    @Transactional
    fun comment(
        actor: BundleActor,
        bunchId: Long,
        id: Long,
        request: BundleTextRequest,
        requestId: String
    ): BundleSnapshot {
        val bundle = access.lock(actor.memberId, bunchId, id)
        mode(bundle, request.mode)
        access.requireParticipant(actor.memberId, bundle)
        val existing = comments.findByBundleIdAndMemberId(id, actor.memberId)
        version(existing?.version ?: 0, request.expectedVersion, mapOf("value" to (existing?.content ?: "")))
        val value = validateText { BundleText.comment(request.value) }
        if (value != (existing?.content ?: "")) {
            val row = existing ?: comments.save(BundleComment(bundleId = id, memberId = actor.memberId))
            row.content = value
            row.version++
            row.updatedAt = Instant.now()
            changed(bundle, requestId, BundleEventType.COMMENT, commentResponse(row))
            recordActivity(bundle, actor.memberId, comment = true)
            if (value.isNotBlank()) {
                participants.findByBundleIdAndMemberId(id, actor.memberId)?.let {
                    if (it.remindersCancelledAt == null) it.remindersCancelledAt = clock.instant()
                    pushJobs.cancelCommentReminderForMember(id, actor.memberId)
                }
            }
            notifyReady(bundle, actor.memberId)
        }
        return snapshot(bundle, actor.memberId)
    }

    @Transactional
    fun caption(
        actor: BundleActor,
        bunchId: Long,
        id: Long,
        photoId: Long,
        request: BundleTextRequest,
        requestId: String
    ): BundleSnapshot {
        val bundle = editable(actor, bunchId, id, request.mode)
        val photo = bundlePhoto(id, photoId)
        leases.requireLease(bundle, actor, "PHOTO:$photoId", request.leaseToken)
        version(photo.captionVersion, request.expectedVersion, mapOf(
            "value" to photo.caption,
            "bundlePhotoId" to photoId
        ))
        val value = validateText { BundleText.caption(request.value) }
        if (photo.caption != value) {
            photo.caption = value
            photo.captionVersion++
            photo.captionUpdatedBy = actor.memberId
            photo.captionUpdatedAt = Instant.now()
            changed(bundle, requestId, BundleEventType.CAPTION, photoResponse(photo))
            recordActivity(bundle, actor.memberId)
        }
        return snapshot(bundle, actor.memberId)
    }

    @Transactional
    fun addParticipant(actor: BundleActor, bunchId: Long, id: Long, memberId: Long, requestId: String): BundleSnapshot {
        val bundle = access.lock(actor.memberId, bunchId, id)
        access.requireParticipant(actor.memberId, bundle)
        access.requireMember(memberId, bunchId)
        if (!access.isParticipant(memberId, id)) {
            val participant = participants.saveAndFlush(
                BundleParticipant(bundleId = id, memberId = memberId, joinedAt = clock.instant())
            )
            // A newly added participation starts above every prior comment version. Removing
            // and re-adding a member must not let an old expectedVersion=0 save become valid.
            comments.saveAndFlush(BundleComment(bundleId = id, memberId = memberId, version = bundle.revision + 1))
            changed(bundle, requestId, BundleEventType.PARTICIPANTS, participantChanges(bundle))
            notifications.participantAdded(bundle, participant, actor.memberId)
        }
        return snapshot(bundle, actor.memberId)
    }

    @Transactional
    fun removeParticipant(
        actor: BundleActor,
        bunchId: Long,
        id: Long,
        memberId: Long,
        requestId: String
    ): BundleSnapshot {
        val bundle = access.lock(actor.memberId, bunchId, id)
        access.requireDraftManager(actor.memberId, bundle)
        if (memberId == bundle.createdBy) fail(ErrorCode.FORBIDDEN)
        removeParticipation(bundle, memberId, requestId, actor.memberId)
        return snapshot(bundle, actor.memberId)
    }

    @Transactional
    fun leave(actor: BundleActor, bunchId: Long, id: Long, requestId: String): BundleSnapshot? {
        val bundle = access.lock(actor.memberId, bunchId, id)
        access.requireParticipant(actor.memberId, bundle)
        if (bundle.createdBy == actor.memberId) {
            deleteInternal(bundle, requestId)
            return null
        }
        removeParticipation(bundle, actor.memberId, requestId, actor.memberId)
        return snapshot(bundle, actor.memberId)
    }

    @Transactional
    fun changePhotos(
        actor: BundleActor,
        bunchId: Long,
        id: Long,
        key: UUID,
        request: BundlePhotoChangesRequest
    ): BundleSnapshot {
        val bundle = editable(actor, bunchId, id, request.mode)
        val operation = "PHOTOS:$id"
        // Optional fields must not change receipts issued by older clients.
        val requestContent = linkedMapOf<String, Any>(
            "addPhotoIds" to request.addPhotoIds,
            "removeBundlePhotoIds" to request.removeBundlePhotoIds,
            "mode" to request.mode
        )
        if (request.capacityMode != BundlePhotoCapacityMode.ATOMIC_APPLY) {
            requestContent["capacityMode"] = request.capacityMode
        }
        if (request.reselectedPhotoIds.isNotEmpty()) {
            requestContent["reselectedPhotoIds"] = request.reselectedPhotoIds
        }
        val fingerprint = fingerprint(requestContent)
        replay(actor.memberId, bunchId, operation, key, fingerprint)?.let {
            return snapshot(it, actor.memberId).copy(
                photoChangeResult = BundlePhotoChangeResult(emptyList(), emptyList(), replayed = true)
            )
        }
        if (request.addPhotoIds.size > 20 || request.removeBundlePhotoIds.size > 20 ||
            request.reselectedPhotoIds.size > 20) fail(ErrorCode.INVALID_INPUT)
        validIds(request.addPhotoIds)
        validIds(request.removeBundlePhotoIds)
        validIds(request.reselectedPhotoIds)
        val reselectedIds = request.reselectedPhotoIds.toSet()
        if (!request.addPhotoIds.containsAll(reselectedIds)) fail(ErrorCode.INVALID_INPUT)
        val current = photoRows(id)
        // Removing a photo already removed by another editor is a no-op; a foreign ID is never accepted.
        request.removeBundlePhotoIds.forEach { removedId ->
            photos.findById(removedId).orElse(null)?.let {
                if (it.bundleId != id) fail(ErrorCode.INVALID_INPUT)
            }
        }
        val removed = current.filter { it.id in request.removeBundlePhotoIds }
        if (removed.any { it.photoId in request.addPhotoIds }) fail(ErrorCode.INVALID_INPUT)
        val kept = current.filterNot { it.id in request.removeBundlePhotoIds }
        val additions = request.addPhotoIds.filterNot { photoId ->
            photoId in reselectedIds || kept.any { it.photoId == photoId }
        }
        if (request.capacityMode == BundlePhotoCapacityMode.PARTIAL_ADD &&
            (request.removeBundlePhotoIds.isNotEmpty() || reselectedIds.isNotEmpty())) {
            fail(ErrorCode.INVALID_INPUT)
        }
        if (kept.isEmpty() && additions.isEmpty()) fail(ErrorCode.BUNDLE_PHOTO_CONFLICT, content(bundle))
        if (request.capacityMode == BundlePhotoCapacityMode.ATOMIC_APPLY && kept.size + additions.size > 20) {
            fail(ErrorCode.BUNDLE_PHOTO_CONFLICT, content(bundle))
        }
        // Validate every submitted source before applying capacity, including overflow IDs.
        val available = if (additions.isEmpty()) emptyList()
            else photoService.lockReadyPhotosForConnection(bunchId, additions)
        val selected = if (request.capacityMode == BundlePhotoCapacityMode.PARTIAL_ADD) {
            available.take(20 - kept.size)
        } else available
        photoOrder.beforeRemoveLocked(bundle, removed)
        photos.deleteAll(removed)
        photos.flush()
        removed.forEach {
            leases.clearPhoto(id, it.id)
            images.releaseAfterCommit(it.imageAsset.id)
        }
        val reselected = kept.filter { it.photoId in reselectedIds }
        val final = kept.filterNot { it.photoId in reselectedIds }.toMutableList()
        val appended = selected.associate { photo ->
            photo.id to addPhoto(bundle, photo, kept.size, actor.memberId)
        } + reselected.associateBy { it.photoId }
        request.addPhotoIds.mapNotNull { appended[it] }.forEach(final::add)
        photoOrder.clearReselectedLocked(bundle, reselected)
        val reordered = final.map { it.id } != current.map { it.id }
        final.forEachIndexed { index, photo -> photo.position = index }
        if (removed.isNotEmpty() || selected.isNotEmpty() || reordered) {
            changed(bundle, key.toString(), BundleEventType.PHOTOS, photoChanges(bundle))
            if (selected.isNotEmpty()) {
                notifications.draftActivity(actor.memberId, bundle, key.toString(), selected.size)
            }
            notifyReady(bundle, actor.memberId)
            recordActivity(bundle, actor.memberId)
        }
        remember(actor.memberId, bundle, operation, key, fingerprint)
        return snapshot(bundle, actor.memberId).copy(
            photoChangeResult = BundlePhotoChangeResult(
                selected.map { it.id }, additions.drop(selected.size)
            )
        )
    }

    @Transactional
    fun move(
        actor: BundleActor,
        bunchId: Long,
        id: Long,
        photoId: Long,
        request: MoveBundlePhotoRequest,
        requestId: String
    ): BundleSnapshot {
        val bundle = editable(actor, bunchId, id, request.mode)
        if (photoOrder.moveLocked(
                bundle, actor.memberId, photoId,
                request.previousBundlePhotoId, request.nextBundlePhotoId, requestId
            )) {
            changed(bundle, requestId, BundleEventType.PHOTOS, photoChanges(bundle))
            recordActivity(bundle, actor.memberId)
        }
        return snapshot(bundle, actor.memberId)
    }

    @Transactional
    fun publish(actor: BundleActor, bunchId: Long, id: Long, key: UUID, request: PublishBundleRequest): BundleSnapshot {
        val bundle = access.lock(actor.memberId, bunchId, id)
        if (!access.isParticipant(actor.memberId, id) ||
            (bundle.createdBy != actor.memberId && !access.isAdministrator(actor.memberId, bunchId))) {
            fail(ErrorCode.FORBIDDEN)
        }
        val operation = "PUBLISH:$id"
        val fingerprint = if (request.visibility == null) {
            // Legacy retries must keep the original expectedRevision-only fingerprint.
            fingerprint(mapOf("expectedRevision" to request.expectedRevision))
        } else fingerprint(request)
        replay(actor.memberId, bunchId, operation, key, fingerprint)?.let { return snapshot(it, actor.memberId) }
        if (bundle.status == BundleStatus.PUBLISHED) {
            // A different key retry is still a single publication; only the originally confirmed revision is valid.
            val original = requests.findAllByBundleIdAndOperationOrderByIdAsc(id, operation).firstOrNull()
            if (original?.fingerprint != fingerprint) fail(ErrorCode.BUNDLE_STATE_CONFLICT)
            remember(actor.memberId, bundle, operation, key, fingerprint)
            return snapshot(bundle, actor.memberId)
        }
        access.requireParticipant(actor.memberId, bundle)
        request.visibility?.let { selected ->
            if (bundle.visibility != selected) {
                bundle.visibility = selected
                bundle.visibilityVersion++
            }
        }
        val blockers = publishBlockers(bundle) +
            if (bundle.visibility == null) listOf("VISIBILITY_REQUIRED") else emptyList()
        if (blockers.isNotEmpty()) fail(ErrorCode.BUNDLE_PUBLISH_BLOCKED, mapOf("reasons" to blockers))
        val originalIds = photoRows(id).map { it.photoId ?: fail(ErrorCode.BUNDLE_PUBLISH_BLOCKED) }
        photoService.lockReadyPhotosForConnection(bunchId, originalIds)
        val beforeCount = bundles.countByBunchIdAndStatus(bunchId, BundleStatus.PUBLISHED)
        bundle.status = BundleStatus.PUBLISHED
        bundle.publishedAt = clock.instant()
        bundle.publishedBy = actor.memberId
        pushJobs.cancelCommentRemindersForBundle(id)
        leases.clear(id)
        changed(bundle, key.toString(), BundleEventType.PUBLISHED, content(bundle))
        remember(actor.memberId, bundle, operation, key, fingerprint)
        bundles.flush()
        notifications.published(
            bundle, actor.memberId, stagePolicy.stage(beforeCount + 1) > stagePolicy.stage(beforeCount)
        )
        return snapshot(bundle, actor.memberId)
    }

    @Transactional
    fun editPublished(
        actor: BundleActor,
        bunchId: Long,
        id: Long,
        request: EditPublishedBundleRequest,
        requestId: String
    ): BundleSnapshot {
        val bundle = access.lock(actor.memberId, bunchId, id)
        access.requireOwner(actor.memberId, bunchId)
        mode(bundle, BundleStatus.PUBLISHED)
        version(bundle.revision, request.expectedRevision, mapOf("revision" to bundle.revision))
        val title = validateText { BundleText.title(request.title) }
        if (title.isBlank() || request.photos.size !in 1..20 ||
            request.date?.isAfter(LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Seoul"))) == true) {
            fail(ErrorCode.INVALID_INPUT)
        }
        val captions = request.photos.map { validateText { BundleText.caption(it.caption) } }
        request.photos.forEach {
            if ((it.bundlePhotoId == null) == (it.photoId == null)) fail(ErrorCode.INVALID_INPUT)
        }
        val retainedIds = request.photos.mapNotNull { it.bundlePhotoId }
        val addedIds = request.photos.mapNotNull { it.photoId }
        validIds(retainedIds)
        validIds(addedIds)
        val current = photoRows(id)
        val byId = current.associateBy { it.id }
        if (retainedIds.any { it !in byId }) fail(ErrorCode.INVALID_INPUT)
        val sourceIds = retainedIds.mapNotNull { byId.getValue(it).photoId } + addedIds
        validIds(sourceIds)
        // Reuse a retained bundle row rather than replace its provenance through a new-photo input.
        if (addedIds.any { sourceId -> current.any { it.photoId == sourceId } }) fail(ErrorCode.INVALID_INPUT)
        val additions = if (addedIds.isEmpty()) emptyMap() else
            photoService.lockReadyPhotosForConnection(bunchId, addedIds).associateBy { it.id }
        val locationChange = locations.replaceLocked(bundle, request.locations)
        val removed = current.filterNot { it.id in retainedIds }
        var edited = locationChange.changed || removed.isNotEmpty() || addedIds.isNotEmpty()
        photoOrder.beforeRemoveLocked(bundle, removed)
        photos.deleteAll(removed)
        photos.flush()
        removed.forEach { images.releaseAfterCommit(it.imageAsset.id) }
        request.photos.forEachIndexed { index, input ->
            val row = input.bundlePhotoId?.let { byId.getValue(it) }
                ?: addPhoto(bundle, additions.getValue(input.photoId!!), index, actor.memberId)
            if (row.position != index) {
                row.position = index
                edited = true
            }
            if (row.caption != captions[index]) {
                row.caption = captions[index]
                row.captionVersion++
                row.captionUpdatedBy = actor.memberId
                row.captionUpdatedAt = clock.instant()
                edited = true
            }
        }
        if (bundle.title != title) {
            bundle.title = title
            bundle.titleVersion++
            edited = true
        }
        if (bundle.visibility != request.visibility) {
            bundle.visibility = request.visibility
            bundle.visibilityVersion++
            edited = true
        }
        if (bundle.date != request.date) {
            bundle.date = request.date
            bundle.dateVersion++
            edited = true
        }
        if (edited) changed(bundle, requestId, BundleEventType.EDITED, content(bundle))
        return snapshot(bundle, actor.memberId)
    }

    @Transactional
    fun delete(actor: BundleActor, bunchId: Long, id: Long, requestId: String) {
        val bundle = access.lock(actor.memberId, bunchId, id)
        if (bundle.status == BundleStatus.DRAFT) access.requireDraftManager(actor.memberId, bundle)
        else access.requireOwner(actor.memberId, bunchId)
        deleteInternal(bundle, requestId)
    }

    /** Called by #25 inside its membership-change transaction after locking the bunch. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun memberDeparted(bunchId: Long, memberId: Long) {
        bunches.findLocked(bunchId) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
        bundles.findAllByBunchIdOrderByIdAsc(bunchId).filter { it.status == BundleStatus.DRAFT }.forEach { candidate ->
            val bundle = bundles.findLocked(candidate.id) ?: return@forEach
            if (bundle.createdBy == memberId) deleteInternal(bundle, UUID.randomUUID().toString())
            else removeParticipation(bundle, memberId, UUID.randomUUID().toString(), memberId)
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun beforePhotoDelete(photoId: Long) {
        val references = photos.findAllByPhotoIdOrderByBundleIdAscIdAsc(photoId)
        references.groupBy { it.bundleId }.toSortedMap().forEach { (bundleId, rows) ->
            val bundle = bundles.findLocked(bundleId) ?: return@forEach
            if (bundle.status == BundleStatus.PUBLISHED) {
                rows.forEach { it.photoId = null }
                // Source metadata is part of snapshots; retained image, caption and order remain unchanged.
                changed(bundle, UUID.randomUUID().toString(), BundleEventType.PHOTOS, photoChanges(bundle))
            } else {
                rows.forEach { leases.clearPhoto(bundleId, it.id) }
                photoOrder.beforeRemoveLocked(bundle, rows)
                photos.deleteAll(rows)
                photos.flush()
                val remaining = photoRows(bundleId)
                if (remaining.isEmpty()) deleteInternal(bundle, UUID.randomUUID().toString())
                else {
                    remaining.forEachIndexed { index, photo -> photo.position = index }
                    changed(bundle, UUID.randomUUID().toString(), BundleEventType.PHOTOS, photoChanges(bundle))
                }
            }
        }
        photos.flush()
    }

    private fun editable(actor: BundleActor, bunchId: Long, id: Long, expected: BundleStatus): Bundle {
        val bundle = access.lock(actor.memberId, bunchId, id)
        mode(bundle, expected)
        if (bundle.status == BundleStatus.DRAFT) access.requireParticipant(actor.memberId, bundle)
        else {
            access.requireOwner(actor.memberId, bunchId)
            fail(ErrorCode.BUNDLE_STATE_CONFLICT, mapOf("status" to bundle.status))
        }
        return bundle
    }

    private fun mode(bundle: Bundle, expected: BundleStatus) {
        if (bundle.status != expected) fail(ErrorCode.BUNDLE_STATE_CONFLICT, mapOf("status" to bundle.status))
    }

    private fun removeParticipation(bundle: Bundle, memberId: Long, requestId: String, actorId: Long) {
        val row = participants.findByBundleIdAndMemberId(bundle.id, memberId) ?: return
        pushJobs.cancelCommentReminderForMember(bundle.id, memberId)
        participants.delete(row)
        comments.findByBundleIdAndMemberId(bundle.id, memberId)?.let(comments::delete)
        participants.flush()
        comments.flush()
        leases.clear(bundle.id, memberId)
        changed(
            bundle,
            requestId,
            BundleEventType.PARTICIPANTS,
            participantChanges(bundle) + ("removedCommentMemberIds" to listOf(memberId))
        )
        notifications.participantRemoved(bundle, memberId, actorId)
        if (participants.findAllByBundleIdOrderByJoinedAtAscIdAsc(bundle.id).size == 1) {
            notifications.participantsInsufficient(bundle, actorId)
        }
        notifyReady(bundle, actorId)
    }

    private fun deleteInternal(bundle: Bundle, requestId: String) {
        pushJobs.cancelCommentRemindersForBundle(bundle.id)
        val assets = photoRows(bundle.id).map { it.imageAsset.id }.distinct()
        leases.clear(bundle.id)
        bundle.revision++
        events.publishEvent(BundleChangedEvent(
            bunchId = bundle.bunchId,
            bundleId = bundle.id,
            revision = bundle.revision,
            requestId = requestId,
            type = BundleEventType.DELETED,
            changes = null
        ))
        bundles.delete(bundle)
        bundles.flush()
        assets.forEach(images::releaseAfterCommit)
    }

    private fun replay(userId: Long, bunchId: Long, operation: String, key: UUID, fingerprint: String): Bundle? {
        val previous = requests.findByBunchIdAndMemberIdAndOperationAndRequestKey(
            bunchId, userId, operation, key
        ) ?: return null
        if (previous.fingerprint != fingerprint) fail(ErrorCode.IDEMPOTENCY_CONFLICT)
        return bundles.findById(previous.bundleId).orElseThrow { BusinessException(ErrorCode.RESOURCE_NOT_FOUND) }
    }

    private fun remember(userId: Long, bundle: Bundle, operation: String, key: UUID, fingerprint: String) {
        requests.save(BundleRequest(
            bunchId = bundle.bunchId,
            memberId = userId,
            operation = operation,
            requestKey = key,
            fingerprint = fingerprint,
            bundleId = bundle.id,
            revision = bundle.revision
        ))
    }

    private fun changed(bundle: Bundle, requestId: String, type: BundleEventType, changes: Any?) {
        bundle.revision++
        bundle.updatedAt = clock.instant()
        // All fields in changes are immutable DTO values, captured before the transaction completes.
        events.publishEvent(BundleChangedEvent(bundle.bunchId, bundle.id, bundle.revision, requestId, type, changes))
    }

    private fun recordActivity(bundle: Bundle, memberId: Long, comment: Boolean = false) {
        if (bundle.status == BundleStatus.DRAFT || comment) {
            memberships.findByMemberIdAndBunchIdAndStatus(memberId, bundle.bunchId)
                ?.recordActivity(bundle.updatedAt)
        }
    }

    private fun addPhoto(bundle: Bundle, photo: Photo, position: Int, userId: Long): BundlePhoto = photos.saveAndFlush(
        BundlePhoto(
            bundleId = bundle.id,
            photoId = photo.id,
            imageAsset = photo.imageAsset,
            position = position,
            addedBy = userId
        )
    )

    private fun photoRows(id: Long) = photos.findAllByBundleIdOrderByPositionAscIdAsc(id)

    private fun bundlePhoto(id: Long, photoId: Long) = photos.findById(photoId).orElse(null)
        ?.takeIf { it.bundleId == id } ?: fail(ErrorCode.RESOURCE_NOT_FOUND)

    private fun version(current: Long, expected: Long, values: Map<String, Any?>) {
        if (current != expected) fail(ErrorCode.BUNDLE_VERSION_CONFLICT, values + ("version" to current))
    }

    private fun validIds(ids: List<Long>) {
        if (ids.any { it <= 0 } || ids.distinct().size != ids.size) fail(ErrorCode.INVALID_INPUT)
    }

    // The day of the recommended bundle the draft was made from comes first (4.3.11), then the days of the photos with
    // the most photos first. There are three chips at most.
    internal fun dateChips(bundle: Bundle): List<LocalDate> {
        if (bundle.date != null) return emptyList()
        val today = LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Seoul"))
        val recommended = bundle.recommendedDate?.takeUnless { it.isAfter(today) }
        val ids = photoRows(bundle.id).mapNotNull { it.photoId }
        val photoDays = if (ids.isEmpty()) {
            emptyList()
        } else {
            originalPhotos.findAllByIdInOrderByIdAsc(ids).mapNotNull { it.capturedDate }
                .filterNot { it.isAfter(today) }.groupingBy { it }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<LocalDate, Int>> { it.value }.thenBy { it.key })
                .map { it.key }
        }
        return (listOfNotNull(recommended) + photoDays.filterNot { it == recommended }).take(3)
    }

    private fun notifyReady(bundle: Bundle, actorId: Long) {
        if (bundle.status == BundleStatus.DRAFT && bundle.publishReadyNotifiedAt == null &&
            publishBlockers(bundle).isEmpty()) {
            bundle.publishReadyNotifiedAt = clock.instant()
            notifications.readyToPublish(bundle, actorId)
        }
    }

    private fun publishBlockers(bundle: Bundle): List<String> {
        val result = mutableListOf<String>()
        if (bundle.title.isBlank()) result.add("TITLE_REQUIRED")
        if (BundleText.titleTooLong(bundle.title)) {
            result.add("TITLE_TOO_LONG")
        }
        val people = participants.findAllByBundleIdOrderByJoinedAtAscIdAsc(bundle.id).map { it.memberId }
        val active = memberships.findAllByBunchIdAndStatus(bundle.bunchId).map { it.member.id }.toSet()
        if (people.size < 2) result.add("PARTICIPANTS_REQUIRED")
        if (people.any { it !in active }) result.add("PARTICIPANT_INACTIVE")
        val written = comments.findAllByBundleIdOrderByIdAsc(bundle.id)
            .filter { it.content.isNotBlank() }
            .map { it.memberId }.toSet()
        if (people.any { it !in written }) result.add("COMMENTS_REQUIRED")
        val rows = photoRows(bundle.id)
        if (rows.size !in 1..20) result.add("PHOTOS_REQUIRED")
        return result
    }

    private fun snapshot(
        bundle: Bundle,
        userId: Long,
        likeSummary: SocialLikeSummary? = null,
        commentTotal: Long? = null
    ): BundleSnapshot {
        val draft = bundle.status == BundleStatus.DRAFT
        if (draft && bundle.titleCrdt == null) bundle.titleCrdt = titleCrdt.initialState(bundle.title)
        val people = if (draft) attribution.draft(bundle) else attribution.published(bundle)
        val social = if (draft) SocialLikeSummary() else
            likeSummary ?: likes.summaries(userId, listOf(bundle.id))[bundle.id] ?: SocialLikeSummary()
        val count = if (draft) 0 else commentTotal ?: socialComments.counts(userId, listOf(bundle.id))[bundle.id] ?: 0
        return BundleSnapshot(
            bundle.id, bundle.bunchId, bundle.createdBy, bundle.status, bundle.title, bundle.titleVersion,
            bundle.visibility, bundle.visibilityVersion, bundle.date, bundle.dateVersion, bundle.revision,
            photoRows(bundle.id).map(::photoResponse),
            people.participants,
            people.comments,
            dateChips(bundle), if (draft) publishBlockers(bundle) else emptyList(),
            access.permissions(userId, bundle),
            leases.publicStates(bundle.id), bundle.createdAt, bundle.updatedAt, bundle.publishedAt,
            bundle.locationVersion, locations.listForBundles(listOf(bundle.id)).getValue(bundle.id),
            social.likeCount, social.likedByCurrentBunch, count,
            bundle.publishedBy, publisherNickname(bundle),
            if (draft) bundle.titleCrdt ?: titleCrdt.initialState(bundle.title) else null,
            BundleText.titleTooLong(bundle.title)
        )
    }

    private fun publisherNickname(bundle: Bundle): String? = bundle.publishedBy?.let { memberId ->
        memberships.findByMemberIdAndBunchIdAndStatus(memberId, bundle.bunchId)?.member?.nickname ?: "작성자"
    }

    private fun content(bundle: Bundle): Map<String, Any?> = mapOf(
        "status" to bundle.status, "title" to bundle.title, "titleVersion" to bundle.titleVersion,
        "visibility" to bundle.visibility, "visibilityVersion" to bundle.visibilityVersion,
        "date" to bundle.date, "dateVersion" to bundle.dateVersion, "publishedAt" to bundle.publishedAt,
        "publishedBy" to bundle.publishedBy, "publisherNickname" to publisherNickname(bundle),
        "locationVersion" to bundle.locationVersion,
        "locations" to locations.listForBundles(listOf(bundle.id)).getValue(bundle.id)
    ) + titleChanges(bundle) + photoChanges(bundle) + participantChanges(bundle)

    private fun photoChanges(bundle: Bundle): Map<String, Any?> = mapOf(
        "photos" to photoRows(bundle.id).map(::photoResponse), "dateChips" to dateChips(bundle)
    )

    private fun participantChanges(bundle: Bundle): Map<String, Any?> {
        val people = if (bundle.status == BundleStatus.PUBLISHED) attribution.published(bundle) else attribution.draft(bundle)
        return mapOf(
            "participants" to people.participants,
            "comments" to people.comments
        )
    }

    private fun photoResponse(row: BundlePhoto) = BundlePhotoResponse(
        row.id, row.photoId, row.imageAsset.id, row.imageAsset.url, row.position, row.addedBy, row.addedAt,
        row.caption, row.captionVersion, row.captionUpdatedBy, row.captionUpdatedAt
    )

    private fun commentResponse(row: BundleComment) = BundleCommentResponse(
        row.memberId, row.content, row.version, row.createdAt, row.updatedAt, id = row.id
    )

    private fun fingerprint(value: Any): String = MessageDigest.getInstance("SHA-256")
        .digest(mapper.writeValueAsBytes(value)).joinToString("") { "%02x".format(it) }

    private fun validateText(block: () -> String): String = try {
        block()
    } catch (_: IllegalArgumentException) {
        fail(ErrorCode.INVALID_INPUT)
    }

    private fun fail(code: ErrorCode, detail: Any? = null): Nothing = throw BusinessException(code, detail)
}
