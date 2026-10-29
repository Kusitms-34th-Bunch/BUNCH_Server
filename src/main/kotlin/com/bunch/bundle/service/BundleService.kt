package com.bunch.bundle.service

import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bundle.domain.*
import com.bunch.bundle.dto.*
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.image.service.ImageAssetService
import com.bunch.photo.domain.Photo
import com.bunch.photo.domain.PhotoRepository
import com.bunch.photo.domain.PhotoUploaderRepository
import com.bunch.photo.service.PhotoService
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Service
class BundleService(
    private val access: BundleAccessService,
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
    private val images: ImageAssetService,
    private val events: ApplicationEventPublisher,
    private val mapper: ObjectMapper
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
        val bundle = bundles.saveAndFlush(Bundle(bunchId = bunchId, createdBy = actor.memberId))
        selected.forEachIndexed { index, photo -> addPhoto(bundle, photo, index, actor.memberId) }
        val currentMembers = memberships.findAllByBunchIdAndStatus(bunchId).map { it.member.id }.toSet()
        val initial = linkedSetOf(actor.memberId)
        uploaders.findAllByPhotoIdInOrderByPhotoIdAndIdAsc(request.photoIds)
            .map { it.member.id }.filter { it in currentMembers }.forEach(initial::add)
        initial.forEach { participants.save(BundleParticipant(bundleId = bundle.id, memberId = it)) }
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
        val sort = Sort.by(Sort.Direction.DESC, if (status == BundleStatus.DRAFT) "createdAt" else "publishedAt", "id")
        val result = bundles.findAllByBunchIdAndStatus(bunchId, status, PageRequest.of(page, size, sort))
        return BundlePage(result.content.map { snapshot(it, userId) }, page, size, result.totalElements)
    }

    @Transactional(readOnly = true)
    fun summary(userId: Long, bunchId: Long): BundleSummary {
        access.requireMember(userId, bunchId)
        val published = bundles.countByBunchIdAndStatus(bunchId, BundleStatus.PUBLISHED)
        return BundleSummary(bundles.countByBunchIdAndStatus(bunchId, BundleStatus.DRAFT), published, stage(published))
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
        if (bundle.status == BundleStatus.DRAFT) leases.requireLease(bundle, actor, "TITLE", request.leaseToken)
        version(bundle.titleVersion, request.expectedVersion, mapOf("value" to bundle.title))
        val value = validateText { BundleText.title(request.value) }
        if (bundle.status == BundleStatus.PUBLISHED && value.isBlank()) fail(ErrorCode.INVALID_INPUT)
        if (bundle.title != value) {
            bundle.title = value
            bundle.titleVersion++
            changed(bundle, requestId, BundleEventType.TITLE, mapOf("value" to value, "version" to bundle.titleVersion))
        }
        return snapshot(bundle, actor.memberId)
    }

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
        if (bundle.status == BundleStatus.DRAFT) leases.requireLease(bundle, actor, "DATE", request.leaseToken)
        version(bundle.dateVersion, request.expectedVersion, mapOf("value" to bundle.date))
        if (request.value?.isAfter(LocalDate.now(ZoneId.of("Asia/Seoul"))) == true) fail(ErrorCode.INVALID_INPUT)
        if (bundle.date != request.value) {
            bundle.date = request.value
            bundle.dateVersion++
            changed(bundle, requestId, BundleEventType.DATE, mapOf(
                "value" to bundle.date, "version" to bundle.dateVersion, "dateChips" to dateChips(bundle)
            ))
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
        if (!access.isParticipant(actor.memberId, id)) fail(ErrorCode.FORBIDDEN)
        val existing = comments.findByBundleIdAndMemberId(id, actor.memberId)
        version(existing?.version ?: 0, request.expectedVersion, mapOf("value" to (existing?.content ?: "")))
        val value = validateText { BundleText.comment(request.value) }
        if (bundle.status == BundleStatus.PUBLISHED && value.isBlank()) fail(ErrorCode.INVALID_INPUT)
        if (value != (existing?.content ?: "")) {
            val row = existing ?: comments.save(BundleComment(bundleId = id, memberId = actor.memberId))
            row.content = value
            row.version++
            row.updatedAt = Instant.now()
            changed(bundle, requestId, BundleEventType.COMMENT, commentResponse(row))
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
        if (bundle.status == BundleStatus.DRAFT) {
            leases.requireLease(bundle, actor, "PHOTO:$photoId", request.leaseToken)
        }
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
        }
        return snapshot(bundle, actor.memberId)
    }

    @Transactional
    fun addParticipant(actor: BundleActor, bunchId: Long, id: Long, memberId: Long, requestId: String): BundleSnapshot {
        val bundle = access.lock(actor.memberId, bunchId, id)
        access.requireParticipant(actor.memberId, bundle)
        access.requireMember(memberId, bunchId)
        if (!access.isParticipant(memberId, id)) {
            participants.saveAndFlush(BundleParticipant(bundleId = id, memberId = memberId))
            // A newly added participation starts above every prior comment version. Removing
            // and re-adding a member must not let an old expectedVersion=0 save become valid.
            comments.saveAndFlush(BundleComment(bundleId = id, memberId = memberId, version = bundle.revision + 1))
            changed(bundle, requestId, BundleEventType.PARTICIPANTS, participantChanges(bundle))
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
        access.requireManager(actor.memberId, bundle, participating = true)
        if (memberId == bundle.createdBy) fail(ErrorCode.FORBIDDEN)
        removeParticipation(bundle, memberId, requestId)
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
        removeParticipation(bundle, actor.memberId, requestId)
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
        val fingerprint = fingerprint(request)
        replay(actor.memberId, bunchId, operation, key, fingerprint)?.let { return snapshot(it, actor.memberId) }
        if (request.addPhotoIds.size > 20 || request.removeBundlePhotoIds.size > 20) fail(ErrorCode.INVALID_INPUT)
        validIds(request.addPhotoIds)
        validIds(request.removeBundlePhotoIds)
        val current = photoRows(id)
        // Removing a photo already removed by another editor is a no-op; a foreign ID is never accepted.
        request.removeBundlePhotoIds.forEach { removedId ->
            photos.findById(removedId).orElse(null)?.let {
                if (it.bundleId != id) fail(ErrorCode.INVALID_INPUT)
            }
        }
        val removed = current.filter { it.id in request.removeBundlePhotoIds }
        if (removed.any { it.photoId in request.addPhotoIds }) fail(ErrorCode.INVALID_INPUT)
        if (bundle.status == BundleStatus.DRAFT) {
            removed.forEach { leases.rejectForeignPhoto(id, it.id, actor) }
        }
        val kept = current.filterNot { it.id in request.removeBundlePhotoIds }
        val additions = request.addPhotoIds.filterNot { photoId -> kept.any { it.photoId == photoId } }
        if (kept.size + additions.size !in 1..20) fail(ErrorCode.BUNDLE_PHOTO_CONFLICT, content(bundle))
        val selected = if (additions.isEmpty()) emptyList()
            else photoService.lockReadyPhotosForConnection(bunchId, additions)
        photos.deleteAll(removed)
        photos.flush()
        removed.forEach {
            leases.clearPhoto(id, it.id)
            images.releaseAfterCommit(it.imageAsset.id)
        }
        val final = kept.toMutableList()
        selected.forEach { final.add(addPhoto(bundle, it, final.size, actor.memberId)) }
        final.forEachIndexed { index, photo -> photo.position = index }
        if (removed.isNotEmpty() || selected.isNotEmpty()) {
            changed(bundle, key.toString(), BundleEventType.PHOTOS, photoChanges(bundle))
        }
        remember(actor.memberId, bundle, operation, key, fingerprint)
        return snapshot(bundle, actor.memberId)
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
        val current = photoRows(id)
        val target = current.firstOrNull { it.id == photoId } ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
        if (bundle.status == BundleStatus.DRAFT) leases.rejectForeignPhoto(id, photoId, actor)
        if (request.previousBundlePhotoId == photoId || request.nextBundlePhotoId == photoId) {
            fail(ErrorCode.BUNDLE_PHOTO_CONFLICT, photoChanges(bundle))
        }
        val rest = current.filterNot { it.id == photoId }.toMutableList()
        val previous = request.previousBundlePhotoId?.let { anchor -> rest.indexOfFirst { it.id == anchor } }
        val next = request.nextBundlePhotoId?.let { anchor -> rest.indexOfFirst { it.id == anchor } }
        if (previous == -1 || next == -1 ||
            (previous != null && next != null && next != previous + 1) ||
            (previous == null && next != null && next != 0) ||
            (next == null && previous != null && previous != rest.lastIndex) ||
            (previous == null && next == null && rest.isNotEmpty())) {
            fail(ErrorCode.BUNDLE_PHOTO_CONFLICT, photoChanges(bundle))
        }
        rest.add(next ?: rest.size, target)
        if (rest.map { it.id } != current.map { it.id }) {
            rest.forEachIndexed { index, photo -> photo.position = index }
            changed(bundle, requestId, BundleEventType.PHOTOS, photoChanges(bundle))
        }
        return snapshot(bundle, actor.memberId)
    }

    @Transactional
    fun publish(actor: BundleActor, bunchId: Long, id: Long, key: UUID, request: PublishBundleRequest): BundleSnapshot {
        val bundle = access.lock(actor.memberId, bunchId, id)
        access.requireManager(actor.memberId, bundle)
        val operation = "PUBLISH:$id"
        val fingerprint = fingerprint(request)
        replay(actor.memberId, bunchId, operation, key, fingerprint)?.let { return snapshot(it, actor.memberId) }
        if (bundle.status == BundleStatus.PUBLISHED) {
            // A different key retry is still a single publication; only the originally confirmed revision is valid.
            val original = requests.findAllByBundleIdAndOperationOrderByIdAsc(id, operation).firstOrNull()
            if (original?.fingerprint != fingerprint) fail(ErrorCode.BUNDLE_STATE_CONFLICT)
            remember(actor.memberId, bundle, operation, key, fingerprint)
            return snapshot(bundle, actor.memberId)
        }
        access.requireParticipant(actor.memberId, bundle)
        version(bundle.revision, request.expectedRevision, content(bundle))
        val blockers = publishBlockers(bundle)
        if (blockers.isNotEmpty()) fail(ErrorCode.BUNDLE_PUBLISH_BLOCKED, mapOf("reasons" to blockers))
        val originalIds = photoRows(id).map { it.photoId ?: fail(ErrorCode.BUNDLE_PUBLISH_BLOCKED) }
        photoService.lockReadyPhotosForConnection(bunchId, originalIds)
        bundle.status = BundleStatus.PUBLISHED
        bundle.publishedAt = Instant.now()
        leases.clear(id)
        changed(bundle, key.toString(), BundleEventType.PUBLISHED, content(bundle))
        remember(actor.memberId, bundle, operation, key, fingerprint)
        bundles.flush()
        return snapshot(bundle, actor.memberId)
    }

    @Transactional
    fun delete(actor: BundleActor, bunchId: Long, id: Long, requestId: String) {
        val bundle = access.lock(actor.memberId, bunchId, id)
        access.requireManager(actor.memberId, bundle)
        deleteInternal(bundle, requestId)
    }

    /** Called by #25 inside its membership-change transaction after locking the bunch. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun memberDeparted(bunchId: Long, memberId: Long) {
        bunches.findLocked(bunchId) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
        bundles.findAllByBunchIdOrderByIdAsc(bunchId).filter { it.status == BundleStatus.DRAFT }.forEach { candidate ->
            val bundle = bundles.findLocked(candidate.id) ?: return@forEach
            if (bundle.createdBy == memberId) deleteInternal(bundle, UUID.randomUUID().toString())
            else removeParticipation(bundle, memberId, UUID.randomUUID().toString())
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
        else access.requireManager(actor.memberId, bundle)
        return bundle
    }

    private fun mode(bundle: Bundle, expected: BundleStatus) {
        if (bundle.status != expected) fail(ErrorCode.BUNDLE_STATE_CONFLICT, mapOf("status" to bundle.status))
    }

    private fun removeParticipation(bundle: Bundle, memberId: Long, requestId: String) {
        val row = participants.findByBundleIdAndMemberId(bundle.id, memberId) ?: return
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
    }

    private fun deleteInternal(bundle: Bundle, requestId: String) {
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
        bundle.updatedAt = Instant.now()
        // All fields in changes are immutable DTO values, captured before the transaction completes.
        events.publishEvent(BundleChangedEvent(bundle.bunchId, bundle.id, bundle.revision, requestId, type, changes))
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

    private fun dateChips(bundle: Bundle): List<LocalDate> {
        if (bundle.date != null) return emptyList()
        val ids = photoRows(bundle.id).mapNotNull { it.photoId }
        if (ids.isEmpty()) return emptyList()
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        return originalPhotos.findAllByIdInOrderByIdAsc(ids).mapNotNull { it.capturedDate }
            .filterNot { it.isAfter(today) }.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<LocalDate, Int>> { it.value }.thenBy { it.key })
            .take(3).map { it.key }
    }

    private fun publishBlockers(bundle: Bundle): List<String> {
        val result = mutableListOf<String>()
        if (bundle.title.isBlank()) result.add("TITLE_REQUIRED")
        if (bundle.visibility == null) result.add("VISIBILITY_REQUIRED")
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

    private fun snapshot(bundle: Bundle, userId: Long): BundleSnapshot {
        val administrator = access.isAdministrator(userId, bundle.bunchId)
        val creator = bundle.createdBy == userId
        val participant = access.isParticipant(userId, bundle.id)
        val draft = bundle.status == BundleStatus.DRAFT
        return BundleSnapshot(
            bundle.id, bundle.bunchId, bundle.createdBy, bundle.status, bundle.title, bundle.titleVersion,
            bundle.visibility, bundle.visibilityVersion, bundle.date, bundle.dateVersion, bundle.revision,
            photoRows(bundle.id).map(::photoResponse),
            participants.findAllByBundleIdOrderByJoinedAtAscIdAsc(bundle.id)
                .map { BundleParticipantResponse(it.memberId, it.joinedAt) },
            comments.findAllByBundleIdOrderByIdAsc(bundle.id).map(::commentResponse),
            dateChips(bundle), if (draft) publishBlockers(bundle) else emptyList(),
            BundlePermissions(administrator, creator, participant, if (draft) participant else creator || administrator,
                draft && participant && (creator || administrator), creator || administrator),
            leases.publicStates(bundle.id), bundle.createdAt, bundle.updatedAt, bundle.publishedAt
        )
    }

    private fun content(bundle: Bundle): Map<String, Any?> = mapOf(
        "status" to bundle.status, "title" to bundle.title, "titleVersion" to bundle.titleVersion,
        "visibility" to bundle.visibility, "visibilityVersion" to bundle.visibilityVersion,
        "date" to bundle.date, "dateVersion" to bundle.dateVersion, "publishedAt" to bundle.publishedAt
    ) + photoChanges(bundle) + participantChanges(bundle)

    private fun photoChanges(bundle: Bundle): Map<String, Any?> = mapOf(
        "photos" to photoRows(bundle.id).map(::photoResponse), "dateChips" to dateChips(bundle)
    )

    private fun participantChanges(bundle: Bundle): Map<String, Any?> = mapOf(
        "participants" to participants.findAllByBundleIdOrderByJoinedAtAscIdAsc(bundle.id)
            .map { BundleParticipantResponse(it.memberId, it.joinedAt) },
        "comments" to comments.findAllByBundleIdOrderByIdAsc(bundle.id).map(::commentResponse)
    )

    private fun photoResponse(row: BundlePhoto) = BundlePhotoResponse(
        row.id, row.photoId, row.imageAsset.id, row.imageAsset.url, row.position, row.addedBy, row.addedAt,
        row.caption, row.captionVersion, row.captionUpdatedBy, row.captionUpdatedAt
    )

    private fun commentResponse(row: BundleComment) = BundleCommentResponse(
        row.memberId, row.content, row.version, row.createdAt, row.updatedAt
    )

    private fun fingerprint(value: Any): String = MessageDigest.getInstance("SHA-256")
        .digest(mapper.writeValueAsBytes(value)).joinToString("") { "%02x".format(it) }

    private fun validateText(block: () -> String): String = try {
        block()
    } catch (_: IllegalArgumentException) {
        fail(ErrorCode.INVALID_INPUT)
    }

    private fun stage(count: Long): Int = (count / 4 + 1).coerceAtMost(5).toInt()

    private fun fail(code: ErrorCode, detail: Any? = null): Nothing = throw BusinessException(code, detail)
}
