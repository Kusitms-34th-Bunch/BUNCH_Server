package com.bunch.social.service

import com.bunch.bunch.domain.BunchRepository
import com.bunch.bundle.domain.BundleRepository
import com.bunch.feed.service.FeedAccessService
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.member.domain.AccountStatus
import com.bunch.member.domain.MemberRepository
import com.bunch.notification.service.NotificationWriter
import com.bunch.social.domain.SocialComment
import com.bunch.social.domain.SocialCommentAuthorRepository
import com.bunch.social.domain.SocialCommentRepository
import com.bunch.social.domain.SocialCommentText
import com.bunch.social.dto.CreateSocialCommentRequest
import com.bunch.social.dto.SocialCommentAuthor
import com.bunch.social.dto.SocialCommentBadge
import com.bunch.social.dto.SocialCommentCursor
import com.bunch.social.dto.SocialCommentItem
import com.bunch.social.dto.SocialCommentPage
import com.bunch.social.dto.SocialCommentReportTarget
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.EntityManager
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

@Service
class SocialCommentService(
    private val access: FeedAccessService,
    private val comments: SocialCommentRepository,
    private val authors: SocialCommentAuthorRepository,
    private val members: MemberRepository,
    private val bunches: BunchRepository,
    private val bundles: BundleRepository,
    private val notifications: NotificationWriter,
    private val entityManager: EntityManager,
    private val mapper: ObjectMapper,
    private val clock: Clock
) {
    @Transactional
    fun create(
        userId: Long,
        bundleId: Long,
        key: UUID,
        request: CreateSocialCommentRequest
    ): SocialCommentItem {
        positive(bundleId)
        request.parentId?.let(::positive)
        val content = SocialCommentText.content(request.content)
        members.findLockedById(userId)?.requireCompleted() ?: fail(ErrorCode.USER_NOT_FOUND)
        val actorId = access.requireActor(userId).bunch.id
        val targetId = bundles.findById(bundleId).orElseThrow {
            BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        }.bunchId
        setOf(actorId, targetId).sorted().forEach {
            bunches.findLocked(it) ?: fail(ErrorCode.BUNCH_PROFILE_UNAVAILABLE)
        }
        bundles.findLocked(bundleId) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
        // Discard entities loaded before waiting on locks; transaction-level DB locks remain held.
        entityManager.clear()
        val interaction = access.requireInteraction(userId, bundleId)
        if (interaction.actor.bunch.id != actorId) fail(ErrorCode.CURRENT_BUNCH_REQUIRED)
        val fingerprint = fingerprint(content, request.parentId)
        comments.findByAuthorMemberIdAndActingBunchIdAndBundleIdAndRequestKey(userId, actorId, bundleId, key)
            ?.let {
                val visible = comments.visible(bundleId, it.id, actorId) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
                if (it.fingerprint != fingerprint) fail(ErrorCode.IDEMPOTENCY_CONFLICT)
                return items(userId, actorId, listOf(visible)).single()
            }
        val parent = request.parentId?.let {
            comments.visible(bundleId, it, actorId) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
        }
        if (parent != null && (parent.parentId != null || parent.deletedAt != null)) {
            fail(ErrorCode.BUNDLE_STATE_CONFLICT)
        }
        val bunch = interaction.actor.bunch
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val row = comments.saveAndFlush(
            SocialComment(
                bundleId = bundleId,
                authorMemberId = userId,
                actingBunchId = actorId,
                badgeName = bunch.name,
                badgeCharacterType = bunch.characterType.name,
                parentId = parent?.id,
                content = content,
                requestKey = key,
                fingerprint = fingerprint,
                createdAt = now
            )
        )
        notifications.socialCommentCreated(
            bundle = interaction.bundle,
            sourceBunch = bunch,
            commentId = row.id,
            actorId = userId,
            content = content,
            parentAuthorId = parent?.authorMemberId,
            parentBunchId = parent?.actingBunchId,
            now = now
        )
        return items(userId, actorId, listOf(row)).single()
    }

    @Transactional
    fun delete(userId: Long, bundleId: Long, commentId: Long): SocialCommentItem {
        positive(bundleId)
        positive(commentId)
        members.findLockedById(userId)?.requireCompleted() ?: fail(ErrorCode.USER_NOT_FOUND)
        access.requireInteraction(userId, bundleId)
        val row = comments.findLocked(bundleId, commentId) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
        val interaction = access.requireInteraction(userId, bundleId)
        if (row.authorMemberId != userId) fail(ErrorCode.FORBIDDEN)
        if (row.deletedAt == null) {
            row.content = ""
            row.deletedAt = clock.instant().truncatedTo(ChronoUnit.MICROS)
        }
        return items(userId, interaction.actor.bunch.id, listOf(row)).single()
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun list(
        userId: Long,
        bundleId: Long,
        parentId: Long? = null,
        size: Int = 20,
        cursor: String? = null
    ): SocialCommentPage {
        if (size !in 1..50) fail(ErrorCode.INVALID_INPUT)
        access.readPublishedBundle(userId, bundleId)
        val current = access.viewer(userId).currentBunchId
        parentId?.let {
            positive(it)
            val parent = comments.visible(bundleId, it, current) ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
            if (parent.parentId != null) fail(ErrorCode.INVALID_INPUT)
        }
        val after = cursor?.let { decode(it, bundleId, parentId, current) }
        val page = PageRequest.of(0, size + 1)
        val rows = if (parentId == null) {
            comments.roots(bundleId, current, after?.createdAt ?: LATEST, after?.id ?: Long.MAX_VALUE, page)
        } else {
            comments.replies(bundleId, parentId, current, after?.createdAt ?: EARLIEST, after?.id ?: 0, page)
        }
        val hasNext = rows.size > size
        val selected = rows.take(size)
        val next = selected.lastOrNull()?.takeIf { hasNext }?.let {
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                mapper.writeValueAsBytes(SocialCommentCursor(bundleId, parentId, current, it.createdAt, it.id))
            )
        }
        val total = comments.counts(listOf(bundleId), current).singleOrNull()?.total ?: 0
        return SocialCommentPage(items(userId, current, selected), next, hasNext, total)
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun counts(userId: Long, bundleIds: Collection<Long>): Map<Long, Long> {
        val visible = access.visibleBundleIds(userId, bundleIds, forFeed = false)
        if (visible.isEmpty()) return emptyMap()
        val current = access.viewer(userId).currentBunchId
        val totals = comments.counts(visible, current).associate { it.bundleId to it.total }
        return visible.associateWith { totals[it] ?: 0L }
    }

    @Transactional(readOnly = true)
    fun reportTarget(userId: Long, bundleId: Long, commentId: Long): SocialCommentReportTarget {
        positive(commentId)
        val interaction = access.requireInteraction(userId, bundleId)
        val row = comments.reportable(bundleId, commentId, interaction.actor.bunch.id)
            ?: fail(ErrorCode.RESOURCE_NOT_FOUND)
        if (row.authorMemberId == userId) fail(ErrorCode.FORBIDDEN)
        if (row.deletedAt != null) fail(ErrorCode.RESOURCE_NOT_FOUND)
        return SocialCommentReportTarget(row.id, row.bundleId, row.authorMemberId, row.actingBunchId, row.parentId)
    }

    private fun items(userId: Long, current: Long?, rows: List<SocialComment>): List<SocialCommentItem> {
        if (rows.isEmpty()) return emptyList()
        val profiles = authors.profiles(rows.map { it.authorMemberId }.distinct()).associateBy { it.id }
        val roots = rows.filter { it.parentId == null }.map { it.id }
        val replies = if (roots.isEmpty()) emptyMap() else
            comments.replyCounts(roots, current).associate { it.parentId to it.total }
        return rows.map { row ->
            val deleted = row.deletedAt != null
            val profile = profiles[row.authorMemberId]?.takeIf { it.accountStatus == AccountStatus.ACTIVE }
            val author = if (deleted || profile == null) {
                SocialCommentAuthor(null, "작성자", null)
            } else {
                SocialCommentAuthor(profile.id, profile.nickname ?: "작성자", profile.imageUrl)
            }
            SocialCommentItem(
                id = row.id,
                bundleId = row.bundleId,
                parentId = row.parentId,
                content = if (deleted) "삭제된 댓글입니다" else row.content,
                deleted = deleted,
                createdAt = row.createdAt.takeUnless { deleted },
                author = author,
                badge = row.actingBunchId?.takeUnless { deleted }?.let {
                    SocialCommentBadge(it, row.badgeName, row.badgeCharacterType)
                },
                replyCount = replies[row.id] ?: 0,
                canDelete = !deleted && row.authorMemberId == userId && current != null,
                canReply = !deleted && row.parentId == null && current != null,
                canReport = !deleted && row.authorMemberId != userId && current != null
            )
        }
    }

    private fun decode(value: String, bundleId: Long, parentId: Long?, current: Long?): SocialCommentCursor {
        if (value.length > 1024) fail(ErrorCode.INVALID_INPUT)
        val cursor = try {
            mapper.readValue(Base64.getUrlDecoder().decode(value), SocialCommentCursor::class.java)
        } catch (_: Exception) {
            fail(ErrorCode.INVALID_INPUT)
        }
        if (cursor.bundleId != bundleId || cursor.parentId != parentId || cursor.currentBunchId != current ||
            cursor.id <= 0 || cursor.createdAt < EARLIEST || cursor.createdAt > LATEST) {
            fail(ErrorCode.INVALID_INPUT)
        }
        return cursor
    }

    private fun fingerprint(content: String, parentId: Long?): String = MessageDigest.getInstance("SHA-256")
        .digest(mapper.writeValueAsBytes(CreateSocialCommentRequest(content, parentId)))
        .joinToString("") { "%02x".format(it) }

    private fun positive(id: Long) {
        if (id <= 0) fail(ErrorCode.INVALID_INPUT)
    }

    private fun fail(code: ErrorCode): Nothing = throw BusinessException(code)

    private companion object {
        val EARLIEST: Instant = Instant.parse("0001-01-01T00:00:00Z")
        val LATEST: Instant = Instant.parse("9999-12-31T23:59:59Z")
    }
}
