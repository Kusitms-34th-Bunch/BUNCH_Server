package com.bunch.bundle.service

import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.domain.MembershipRole
import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.domain.BundleParticipantRepository
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.domain.BundleStatus
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.member.domain.MemberRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class BundleAccessService(
    private val bunches: BunchRepository,
    private val memberships: BunchMembershipRepository,
    private val members: MemberRepository,
    private val bundles: BundleRepository,
    private val participants: BundleParticipantRepository
) {
    @Transactional(readOnly = true)
    fun requireMember(userId: Long, bunchId: Long) {
        members.findById(userId).orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }.requireCompleted()
        if (memberships.findByMemberIdAndBunchIdAndStatus(userId, bunchId) == null) fail(ErrorCode.FORBIDDEN)
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
        bunches.findLocked(bunchId) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
        requireMember(userId, bunchId)
    }

    fun isAdministrator(userId: Long, bunchId: Long): Boolean =
        memberships.findByMemberIdAndBunchIdAndStatus(userId, bunchId)?.role == MembershipRole.OWNER

    fun isParticipant(userId: Long, bundleId: Long): Boolean =
        participants.findByBundleIdAndMemberId(bundleId, userId) != null

    fun requireParticipant(userId: Long, bundle: Bundle) {
        if (bundle.status != BundleStatus.DRAFT) fail(ErrorCode.BUNDLE_STATE_CONFLICT)
        if (!isParticipant(userId, bundle.id)) fail(ErrorCode.FORBIDDEN)
    }

    fun requireManager(userId: Long, bundle: Bundle, participating: Boolean = false) {
        if (participating) requireParticipant(userId, bundle)
        if (bundle.createdBy != userId && !isAdministrator(userId, bundle.bunchId)) fail(ErrorCode.FORBIDDEN)
    }

    private fun fail(code: ErrorCode): Nothing = throw BusinessException(code)
}
