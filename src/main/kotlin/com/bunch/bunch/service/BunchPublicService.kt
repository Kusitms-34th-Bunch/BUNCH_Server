package com.bunch.bunch.service

import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bunch.domain.BunchPublicRepository
import com.bunch.bunch.domain.MembershipStatus
import com.bunch.bunch.dto.BunchPublicBundleCard
import com.bunch.bunch.dto.BunchPublicBundleComment
import com.bunch.bunch.dto.BunchPublicBundleContributor
import com.bunch.bunch.dto.BunchPublicBundleDetail
import com.bunch.bunch.dto.BunchPublicBundlePhoto
import com.bunch.bunch.dto.BunchMemberResponse
import com.bunch.bunch.dto.BunchSocialPage
import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.domain.BundleComment
import com.bunch.bundle.domain.BundleParticipant
import com.bunch.bundle.domain.BundleCommentRepository
import com.bunch.bundle.domain.BundleParticipantRepository
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.member.domain.AccountStatus
import com.bunch.member.domain.Member
import com.bunch.member.domain.MemberRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
class BunchPublicService(
    private val access: BunchSocialAccessService,
    private val publicRepository: BunchPublicRepository,
    private val memberships: BunchMembershipRepository,
    private val participants: BundleParticipantRepository,
    private val comments: BundleCommentRepository,
    private val members: MemberRepository
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun members(userId: Long, targetId: Long): List<BunchMemberResponse> {
        val target = access.target(userId, targetId, requireContent = true)
        return memberships.findActiveMembers(target.id).map(BunchMemberResponse::from)
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun bundles(
        userId: Long,
        targetId: Long,
        page: Int,
        size: Int
    ): BunchSocialPage<BunchPublicBundleCard> {
        val pageable = pageable(page, size)
        val target = access.target(userId, targetId, requireContent = true)
        val internal = access.current(userId)?.bunch?.id == target.id
        val result = publicRepository.findPublishedBundles(target.id, internal, pageable)
        val coverImageUrls = if (result.content.isEmpty()) {
            emptyMap()
        } else {
            publicRepository.findFirstCoverImages(result.content.map { it.id })
                .associate { it.bundleId to it.imageUrl }
        }
        val items = result.content.map { bundle ->
            bundleCard(bundle, coverImageUrls[bundle.id])
        }
        return BunchSocialPage(
            items = items,
            page = page,
            size = size,
            totalElements = result.totalElements,
            hasNext = result.hasNext()
        )
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun bundle(
        userId: Long,
        targetId: Long,
        bundleId: Long
    ): BunchPublicBundleDetail {
        positive(bundleId)
        val target = access.target(userId, targetId, requireContent = true)
        val internal = access.current(userId)?.bunch?.id == target.id
        val bundle = publicRepository.findPublishedBundle(target.id, bundleId, internal)
            ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        val photos = publicRepository.findBundlePhotos(bundle.id)
        val participantRows = participants.findAllByBundleIdOrderByJoinedAtAscIdAsc(bundle.id)
        val commentRows = comments.findAllByBundleIdOrderByIdAsc(bundle.id)
        return bundleDetail(bundle, photos, participantRows, commentRows)
    }

    private fun bundleCard(bundle: Bundle, coverImageUrl: String?): BunchPublicBundleCard {
        return BunchPublicBundleCard(
            id = bundle.id,
            bunchId = bundle.bunchId,
            title = bundle.title,
            coverImageUrl = coverImageUrl,
            date = bundle.date,
            publishedAt = bundle.publishedAt
        )
    }

    private fun bundleDetail(
        bundle: Bundle,
        photoRows: List<BunchPublicBundlePhoto>,
        participantRows: List<BundleParticipant>,
        commentRows: List<BundleComment>
    ): BunchPublicBundleDetail {
        val memberIds = (participantRows.map { it.memberId } + commentRows.map { it.memberId }).distinct()
        val memberById = members.findAllById(memberIds).associateBy(Member::id)
        val activeMemberIds = memberships.findAllByBunchIdAndStatus(bundle.bunchId, MembershipStatus.ACTIVE)
            .filter { it.member.accountStatus == AccountStatus.ACTIVE }
            .map { it.member.id }
            .toSet()

        return BunchPublicBundleDetail(
            id = bundle.id,
            bunchId = bundle.bunchId,
            title = bundle.title,
            coverImageUrl = photoRows.firstOrNull()?.imageUrl,
            date = bundle.date,
            publishedAt = bundle.publishedAt,
            photos = photoRows,
            contributors = participantRows.map { participant ->
                contributor(participant.memberId, memberById[participant.memberId], activeMemberIds)
            },
            comments = commentRows.map { comment ->
                comment(comment, memberById[comment.memberId], activeMemberIds)
            }
        )
    }

    private fun contributor(
        memberId: Long,
        member: Member?,
        activeMemberIds: Set<Long>
    ): BunchPublicBundleContributor {
        val current = member
        if (current == null || current.accountStatus == AccountStatus.WITHDRAWN) {
            return BunchPublicBundleContributor(memberId, "탈퇴한 멤버", null)
        }
        if (current.accountStatus == AccountStatus.ACTIVE && memberId in activeMemberIds) {
            return BunchPublicBundleContributor(memberId, current.nickname, current.imageUrl)
        }
        return BunchPublicBundleContributor(memberId, "나간 멤버", null)
    }

    private fun comment(
        comment: BundleComment,
        member: Member?,
        activeMemberIds: Set<Long>
    ): BunchPublicBundleComment {
        val current = member
        if (current == null || current.accountStatus == AccountStatus.WITHDRAWN) {
            return BunchPublicBundleComment(
                id = comment.id,
                memberId = comment.memberId,
                nickname = "탈퇴한 멤버",
                imageUrl = null,
                content = null,
                updatedAt = comment.updatedAt
            )
        }

        val active = current.accountStatus == AccountStatus.ACTIVE && comment.memberId in activeMemberIds
        return BunchPublicBundleComment(
            id = comment.id,
            memberId = comment.memberId,
            nickname = if (active) current.nickname else "나간 멤버",
            imageUrl = if (active) current.imageUrl else null,
            content = comment.content,
            updatedAt = comment.updatedAt
        )
    }

    private fun pageable(page: Int, size: Int): PageRequest {
        if (page < 0 || size !in 1..50 || page.toLong() * size > Int.MAX_VALUE) {
            throw BusinessException(ErrorCode.INVALID_INPUT)
        }
        return PageRequest.of(page, size, Sort.unsorted())
    }

    private fun positive(id: Long) {
        if (id <= 0) throw BusinessException(ErrorCode.INVALID_INPUT)
    }
}
