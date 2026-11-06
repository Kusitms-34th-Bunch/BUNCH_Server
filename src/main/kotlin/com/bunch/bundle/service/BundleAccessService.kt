package com.bunch.bundle.service

import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.domain.MembershipRole
import com.bunch.bunch.domain.MembershipStatus
import com.bunch.bunch.service.BunchAccessNoticeService
import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.domain.BundleParticipantRepository
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.domain.BundleStatus
import com.bunch.bundle.dto.BundlePermissions
import com.bunch.feed.service.FeedAccessService
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class BundleAccessService(
    private val bunches: BunchRepository,
    private val memberships: BunchMembershipRepository,
    private val accessNotices: BunchAccessNoticeService,
    private val bundles: BundleRepository,
    private val participants: BundleParticipantRepository,
    private val feedAccess: FeedAccessService,
    private val entityManager: EntityManager
) {
    @Transactional(readOnly = true)
    fun requireMember(userId: Long, bunchId: Long) {
        accessNotices.requireMemberOrAccessNotice(userId, bunchId)
    }

    @Transactional(readOnly = true)
    fun read(userId: Long, bunchId: Long, bundleId: Long): Bundle {
        requireMember(userId, bunchId)
        val bundle = bundles.findById(bundleId).orElseThrow { BusinessException(ErrorCode.RESOURCE_NOT_FOUND) }
        if (bundle.bunchId != bunchId) fail(ErrorCode.RESOURCE_NOT_FOUND)
        if (bundle.status == BundleStatus.PUBLISHED) feedAccess.readPublishedBundle(userId, bundleId)
        return bundle
    }

    @Transactional
    fun lock(userId: Long, bunchId: Long, bundleId: Long): Bundle {
        lockBunch(userId, bunchId)
        val bundle = bundles.findLocked(bundleId) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
        entityManager.flush()
        entityManager.refresh(bundle)
        if (bundle.bunchId != bunchId) fail(ErrorCode.RESOURCE_NOT_FOUND)
        if (bundle.status == BundleStatus.PUBLISHED) feedAccess.readPublishedBundle(userId, bundleId)
        return bundle
    }

    @Transactional
    fun lockBunch(userId: Long, bunchId: Long) {
        requireMember(userId, bunchId)
        val bunch = bunches.findLocked(bunchId) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
        // A parent transaction may already hold these entities. Preserve its pending writes,
        // then reload only this authorization context after waiting on the bunch lock.
        entityManager.flush()
        entityManager.refresh(bunch)
        val membership = memberships.findByMemberIdAndBunchIdAndStatus(userId, bunchId) ?: run {
            requireMember(userId, bunchId)
            fail(ErrorCode.FORBIDDEN)
        }
        entityManager.refresh(membership)
        entityManager.refresh(membership.member)
        membership.member.requireCompleted()
        if (membership.status != MembershipStatus.ACTIVE) fail(ErrorCode.FORBIDDEN)
        requireMember(userId, bunchId)
    }

    fun permissions(userId: Long, bundle: Bundle): BundlePermissions {
        val administrator = isAdministrator(userId, bundle.bunchId)
        val creator = bundle.createdBy == userId
        val participant = isParticipant(userId, bundle.id)
        val draft = bundle.status == BundleStatus.DRAFT
        val current = if (draft) null else feedAccess.viewer(userId).currentBunchId
        val owner = !draft && current == bundle.bunchId && isOwner(userId, bundle.bunchId)
        val draftManager = participant && (creator || administrator)
        return BundlePermissions(
            administrator, creator, participant,
            canEdit = if (draft) participant else owner,
            canPublish = draft && draftManager,
            canDelete = if (draft) draftManager else owner,
            canReport = !draft && current != null && !owner,
            canLike = !draft && current != null,
            canComment = !draft && current != null
        )
    }

    fun isOwner(userId: Long, bunchId: Long): Boolean =
        memberships.findByMemberIdAndBunchIdAndStatus(userId, bunchId)?.role == MembershipRole.OWNER

    fun isAdministrator(userId: Long, bunchId: Long): Boolean =
        when (memberships.findByMemberIdAndBunchIdAndStatus(userId, bunchId)?.role) {
            MembershipRole.OWNER, MembershipRole.CO_OWNER -> true
            else -> false
        }

    fun isParticipant(userId: Long, bundleId: Long): Boolean =
        participants.findByBundleIdAndMemberId(bundleId, userId) != null

    fun requireParticipant(userId: Long, bundle: Bundle) {
        if (bundle.status != BundleStatus.DRAFT) fail(ErrorCode.BUNDLE_STATE_CONFLICT)
        if (!isParticipant(userId, bundle.id)) fail(ErrorCode.FORBIDDEN)
    }

    fun requireCommentAuthor(userId: Long, bundle: Bundle) {
        requireParticipant(userId, bundle)
    }

    fun requireDraftManager(userId: Long, bundle: Bundle) {
        requireParticipant(userId, bundle)
        if (bundle.createdBy != userId && !isAdministrator(userId, bundle.bunchId)) fail(ErrorCode.FORBIDDEN)
    }

    fun requireOwner(userId: Long, bunchId: Long) {
        if (!isOwner(userId, bunchId)) fail(ErrorCode.FORBIDDEN)
    }

    private fun fail(code: ErrorCode): Nothing = throw BusinessException(code)
}
