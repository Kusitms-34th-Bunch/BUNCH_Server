package com.bunch.bundle.service

import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.domain.MembershipRole
import com.bunch.bunch.service.BunchAccessNoticeService
import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.domain.BundleParticipantRepository
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.domain.BundleStatus
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class BundleAccessService(
    private val bunches: BunchRepository,
    private val memberships: BunchMembershipRepository,
    private val accessNotices: BunchAccessNoticeService,
    private val bundles: BundleRepository,
    private val participants: BundleParticipantRepository
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
        return bundle
    }

    @Transactional
    fun lock(userId: Long, bunchId: Long, bundleId: Long): Bundle {
        lockBunch(userId, bunchId)
        val bundle = bundles.findLocked(bundleId) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
        if (bundle.bunchId != bunchId) fail(ErrorCode.RESOURCE_NOT_FOUND)
        return bundle
    }

    @Transactional
    fun lockBunch(userId: Long, bunchId: Long) {
        requireMember(userId, bunchId)
        bunches.findLocked(bunchId) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
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
        if (!isParticipant(userId, bundle.id)) fail(ErrorCode.FORBIDDEN)
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
