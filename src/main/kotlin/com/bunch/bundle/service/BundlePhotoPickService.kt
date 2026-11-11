package com.bunch.bundle.service

import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.domain.BundleParticipantRepository
import com.bunch.bundle.domain.BundlePhotoPick
import com.bunch.bundle.domain.BundlePhotoPickRepository
import com.bunch.bundle.domain.BundlePhotoRepository
import com.bunch.bundle.dto.BundleParticipantResponse
import com.bunch.bundle.dto.BundlePhotoPickRequest
import com.bunch.bundle.dto.BundlePhotoPicks
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class BundlePhotoPickService(
    private val access: BundleAccessService,
    private val photos: BundlePhotoRepository,
    private val picks: BundlePhotoPickRepository,
    private val participants: BundleParticipantRepository,
    private val attribution: BundleAttributionService,
    private val memberships: BunchMembershipRepository,
    private val entityManager: EntityManager,
    private val clock: Clock
) {
    @Transactional
    fun save(
        userId: Long,
        bunchId: Long,
        bundleId: Long,
        photoId: Long,
        request: BundlePhotoPickRequest
    ): BundlePhotoPicks {
        val bundle = access.lock(userId, bunchId, bundleId)
        access.requireParticipant(userId, bundle)
        if (!photos.existsByIdAndBundleId(photoId, bundleId)) {
            throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        }
        if (request.expectedVersion < 0) throw BusinessException(ErrorCode.INVALID_INPUT)
        val people = attribution.draftParticipants(bundle)
        val row = picks.findByBundlePhotoIdAndMemberId(photoId, userId)
        row?.let(entityManager::refresh)
        if ((row?.picked ?: false) != request.picked) {
            val current = row?.version ?: participants.findPickInitialVersion(bundleId, userId)
                ?: throw BusinessException(ErrorCode.FORBIDDEN)
            if (request.expectedVersion != current) {
                throw BusinessException(
                    ErrorCode.BUNDLE_VERSION_CONFLICT,
                    summaries(bundle, userId, people).getValue(photoId)
                )
            }
            bundle.revision++
            bundle.updatedAt = clock.instant()
            if (row == null) {
                picks.saveAndFlush(BundlePhotoPick(
                    bundleId = bundleId,
                    bundlePhotoId = photoId,
                    memberId = userId,
                    picked = request.picked,
                    version = bundle.revision
                ))
            } else {
                row.picked = request.picked
                row.version = bundle.revision
                picks.flush()
            }
            memberships.findByMemberIdAndBunchIdAndStatus(userId, bunchId)?.recordActivity(bundle.updatedAt)
        }
        // No pick event: this screen refreshes other members' choices only on re-entry.
        return summaries(bundle, userId, people).getValue(photoId)
    }

    /** Internal assembler. The caller authorizes the bundle and supplies its ordered attribution. */
    @Transactional(readOnly = true)
    fun summaries(
        bundle: Bundle,
        userId: Long,
        people: List<BundleParticipantResponse>
    ): Map<Long, BundlePhotoPicks> {
        val initialVersion = participants.findPickInitialVersion(bundle.id, userId) ?: 0L
        val byPhoto = picks.findStatesByBundleId(bundle.id).groupBy { it.bundlePhotoId }
        return photos.findAllByBundleIdOrderByPositionAscIdAsc(bundle.id).associate { photo ->
            val rows = byPhoto[photo.id].orEmpty()
            val selectedIds = rows.filter { it.picked }.map { it.memberId }.toSet()
            val selected = people.filter { it.memberId in selectedIds }
            val own = rows.firstOrNull { it.memberId == userId }
            photo.id to BundlePhotoPicks(selected.size, own?.picked ?: false, own?.version ?: initialVersion, selected)
        }
    }
}
