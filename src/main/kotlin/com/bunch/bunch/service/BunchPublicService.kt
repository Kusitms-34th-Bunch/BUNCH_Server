package com.bunch.bunch.service

import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bunch.domain.BunchPublicRepository
import com.bunch.bunch.dto.BunchPublicBundleCard
import com.bunch.bunch.dto.BunchPublicBundleComment
import com.bunch.bunch.dto.BunchPublicBundleContributor
import com.bunch.bunch.dto.BunchPublicBundleDetail
import com.bunch.bunch.dto.BunchPublicBundlePhoto
import com.bunch.bunch.dto.BunchMemberResponse
import com.bunch.bunch.dto.BunchSocialPage
import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.dto.BundleLocationResponse
import com.bunch.bundle.service.BundlePhotoPickService
import com.bunch.bundle.service.BundleAccessService
import com.bunch.bundle.service.BundleAttributionService
import com.bunch.bundle.service.BundleLocationService
import com.bunch.feed.service.FeedAccessService
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.social.dto.SocialLikeSummary
import com.bunch.social.service.SocialLikeService
import com.bunch.social.service.SocialCommentService
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
class BunchPublicService(
    private val access: BunchSocialAccessService,
    private val feedAccess: FeedAccessService,
    private val publicRepository: BunchPublicRepository,
    private val memberships: BunchMembershipRepository,
    private val bundleAccess: BundleAccessService,
    private val attribution: BundleAttributionService,
    private val picks: BundlePhotoPickService,
    private val locations: BundleLocationService,
    private val likes: SocialLikeService,
    private val socialComments: SocialCommentService
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
        val result = feedAccess.publishedBundles(userId, targetId, pageable)
        val coverImageUrls = if (result.content.isEmpty()) {
            emptyMap()
        } else {
            publicRepository.findFirstCoverImages(result.content.map { it.id })
                .associate { it.bundleId to it.imageUrl }
        }
        val ids = result.content.map { it.id }
        val places = locations.listForBundles(ids)
        val likeTotals = likes.summaries(userId, ids)
        val commentTotals = socialComments.counts(userId, ids)
        val items = result.content.map { bundle ->
            bundleCard(
                bundle, coverImageUrls[bundle.id], places.getValue(bundle.id),
                likeTotals[bundle.id] ?: SocialLikeSummary(), commentTotals[bundle.id] ?: 0
            )
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
        val bundle = feedAccess.readPublishedBundle(userId, bundleId)
        if (bundle.bunchId != target.id) throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        val photos = publicRepository.findBundlePhotos(bundle.id)
        return bundleDetail(userId, bundle, photos)
    }

    private fun bundleCard(
        bundle: Bundle,
        coverImageUrl: String?,
        places: List<BundleLocationResponse>,
        social: SocialLikeSummary,
        commentCount: Long
    ): BunchPublicBundleCard {
        return BunchPublicBundleCard(
            id = bundle.id,
            bunchId = bundle.bunchId,
            title = bundle.title,
            coverImageUrl = coverImageUrl,
            date = bundle.date,
            publishedAt = bundle.publishedAt,
            locations = places,
            likeCount = social.likeCount,
            likedByCurrentBunch = social.likedByCurrentBunch,
            commentCount = commentCount
        )
    }

    private fun bundleDetail(
        userId: Long,
        bundle: Bundle,
        photoRows: List<BunchPublicBundlePhoto>
    ): BunchPublicBundleDetail {
        val people = attribution.published(bundle)
        val photoPicks = picks.summaries(bundle, userId, people.participants)
        val social = likes.summaries(userId, listOf(bundle.id))[bundle.id] ?: SocialLikeSummary()
        return BunchPublicBundleDetail(
            id = bundle.id,
            bunchId = bundle.bunchId,
            title = bundle.title,
            coverImageUrl = photoRows.firstOrNull()?.imageUrl,
            date = bundle.date,
            publishedAt = bundle.publishedAt,
            photos = photoRows.map {
                it.copy(picks = photoPicks.getValue(it.id))
            },
            contributors = people.participants.map {
                BunchPublicBundleContributor(it.memberId, it.nickname, it.imageUrl, it.profileAvailable)
            },
            comments = people.comments.map {
                BunchPublicBundleComment(
                    it.id, it.memberId, it.nickname, it.imageUrl, it.content, it.updatedAt, it.profileAvailable
                )
            },
            locations = locations.listForBundles(listOf(bundle.id)).getValue(bundle.id),
            revision = bundle.revision,
            visibility = bundle.visibility,
            permissions = bundleAccess.permissions(userId, bundle),
            likeCount = social.likeCount,
            likedByCurrentBunch = social.likedByCurrentBunch,
            commentCount = socialComments.counts(userId, listOf(bundle.id))[bundle.id] ?: 0
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
