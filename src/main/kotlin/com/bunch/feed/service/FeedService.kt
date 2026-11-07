package com.bunch.feed.service

import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.service.BundleAttributionService
import com.bunch.bundle.service.BundleLocationService
import com.bunch.feed.domain.FeedCardRepository
import com.bunch.feed.domain.FeedQueryRepository
import com.bunch.feed.domain.FeedSegment
import com.bunch.feed.dto.FeedAdvertisementItem
import com.bunch.feed.dto.FeedBunchInfo
import com.bunch.feed.dto.FeedBundleCard
import com.bunch.feed.dto.FeedBundleItem
import com.bunch.feed.dto.FeedCursor
import com.bunch.feed.dto.FeedFollowingCaughtUpItem
import com.bunch.feed.dto.FeedItem
import com.bunch.feed.dto.FeedPhoto
import com.bunch.feed.dto.FeedResponse
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.social.service.SocialCommentService
import com.bunch.social.service.SocialLikeService
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.temporal.ChronoUnit

@Service
class FeedService(
    private val access: FeedAccessService,
    private val query: FeedQueryRepository,
    private val cardRows: FeedCardRepository,
    private val cursors: FeedCursorCodec,
    private val attribution: BundleAttributionService,
    private val locations: BundleLocationService,
    private val likes: SocialLikeService,
    private val comments: SocialCommentService,
    private val advertisements: AdvertisementService,
    private val clock: Clock
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun get(userId: Long, size: Int = 20, cursor: String? = null): FeedResponse {
        if (size !in 1..50) throw BusinessException(ErrorCode.INVALID_INPUT)
        val viewer = access.viewer(userId)
        val membershipContext = fingerprint(query.membershipContext(userId).map {
            "${it.id}:${it.bunchId}:${it.lastUsedAt}"
        })
        val follows = viewer.currentBunchId?.let(query::approvedFollowing).orEmpty()
        val followingContext = fingerprint(follows.map {
            "${it.id}:${it.followedBunchId}:${it.status}:${it.approvedAt}"
        })
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val start = cursor?.let { cursors.decode(it, viewer, membershipContext, followingContext, now) }
            ?: FeedCursor(
                anchorTime = now,
                segment = if (follows.isEmpty()) FeedSegment.PUBLIC else FeedSegment.FOLLOWING,
                lastTime = null,
                lastId = null,
                viewerId = userId,
                currentBunchId = viewer.currentBunchId,
                membershipContext = membershipContext,
                followingContext = followingContext
            )
        val since = start.anchorTime.minus(14, ChronoUnit.DAYS)
        // A sentinel keeps empty IN parameters portable. It can never match an identity ID.
        val followedIds = follows.map { it.followedBunchId }.ifEmpty { listOf(-1L) }
        val selected = mutableListOf<Pair<Bundle, FeedSegment>>()
        var next = start
        var followingBoundary: Int? = null
        var hasNext = false
        if (start.segment == FeedSegment.FOLLOWING) {
            val rows = query.following(
                userId, viewer.currentBunchId, start.anchorTime, since, followedIds,
                start.lastTime, start.lastId, start.lastTime != null, PageRequest.of(0, size + 1)
            )
            val taken = rows.take(size)
            selected += taken.map { it to FeedSegment.FOLLOWING }
            if (rows.size > size) {
                hasNext = true
                next = start.copy(
                    lastTime = taken.last().publishedAt, lastId = taken.last().id, followingSeen = true
                )
            } else {
                if (start.followingSeen || taken.isNotEmpty()) followingBoundary = taken.size
                next = start.copy(
                    segment = FeedSegment.PUBLIC, lastTime = null, lastId = null,
                    followingSeen = start.followingSeen || taken.isNotEmpty()
                )
            }
        }
        if (next.segment == FeedSegment.PUBLIC) {
            val remaining = size - selected.size
            // Even at an exact section/page boundary, probe PUBLIC before deciding hasNext.
            val rows = query.publicBundles(
                userId, viewer.currentBunchId, start.anchorTime, since, followedIds,
                next.lastTime, next.lastId, next.lastTime != null, PageRequest.of(0, remaining + 1)
            )
            val taken = rows.take(remaining)
            selected += taken.map { it to FeedSegment.PUBLIC }
            hasNext = rows.size > remaining
            if (taken.isNotEmpty()) next = next.copy(lastTime = taken.last().publishedAt, lastId = taken.last().id)
        }

        val cards = cards(userId, selected.map { it.first })
        val slots = (1..selected.size).filter { (start.bundleCount + it) % 6 == 0L }
        val ads = advertisements.selectForFeed(userId, selected.size, slots, start.lastAdId)
        val adByPosition = ads.placements.associateBy { it.afterBundle }
        val items = mutableListOf<FeedItem>()
        selected.forEachIndexed { index, (bundle, segment) ->
            if (followingBoundary == index) items += FeedFollowingCaughtUpItem()
            items += FeedBundleItem(segment, cards.getValue(bundle.id))
            adByPosition[index + 1]?.let { items += FeedAdvertisementItem(it.advertisement) }
        }
        if (followingBoundary == selected.size) items += FeedFollowingCaughtUpItem()
        next = next.copy(bundleCount = start.bundleCount + selected.size, lastAdId = ads.lastAdId)
        return FeedResponse(
            anchorTime = start.anchorTime,
            currentBunchId = viewer.currentBunchId,
            canInteract = viewer.currentBunchId != null,
            size = size,
            bundleCount = selected.size,
            items = items,
            nextCursor = if (hasNext) cursors.encode(next) else null,
            hasNext = hasNext,
            endReached = !hasNext
        )
    }

    private fun cards(userId: Long, bundles: List<Bundle>): Map<Long, FeedBundleCard> {
        if (bundles.isEmpty()) return emptyMap()
        val ids = bundles.map { it.id }
        val photos = cardRows.photos(ids).groupBy { it.bundleId }
        val bunches = cardRows.bunches(bundles.map { it.bunchId }.distinct()).associateBy { it.id }
        val people = attribution.publishedAll(bundles)
        val places = locations.listForBundles(ids)
        val likeCounts = likes.summaries(userId, ids)
        val commentCounts = comments.counts(userId, ids)
        return bundles.associate { bundle ->
            val profile = bunches.getValue(bundle.bunchId)
            val images = photos[bundle.id].orEmpty().map {
                FeedPhoto(it.id, it.imageAsset.url, it.position, it.caption)
            }
            val reactions = likeCounts.getValue(bundle.id)
            bundle.id to FeedBundleCard(
                id = bundle.id,
                bunch = FeedBunchInfo(
                    profile.id, profile.name, profile.imageUrl, profile.characterType, profile.visibility
                ),
                publishedAt = requireNotNull(bundle.publishedAt),
                title = bundle.title,
                date = bundle.date,
                coverImageUrl = images.firstOrNull()?.imageUrl,
                photos = images,
                locations = places.getValue(bundle.id),
                participants = people.getValue(bundle.id).participants,
                likeCount = reactions.likeCount,
                likedByCurrentBunch = reactions.likedByCurrentBunch,
                commentCount = commentCounts.getValue(bundle.id)
            )
        }
    }

    private fun fingerprint(values: List<String>): String = MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString("\n").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
