package com.bunch.social.dto

import java.time.Instant

data class CreateSocialCommentRequest(val content: String, val parentId: Long? = null)

data class SocialCommentAuthor(val memberId: Long?, val nickname: String, val imageUrl: String?)

data class SocialCommentBadge(val bunchId: Long, val name: String, val characterType: String)

data class SocialCommentItem(
    val id: Long,
    val bundleId: Long,
    val parentId: Long?,
    val content: String,
    val deleted: Boolean,
    val createdAt: Instant?,
    val author: SocialCommentAuthor,
    val badge: SocialCommentBadge?,
    val replyCount: Long,
    val canDelete: Boolean,
    val canReply: Boolean,
    val canReport: Boolean
)

data class SocialCommentPage(
    val items: List<SocialCommentItem>,
    val nextCursor: String?,
    val hasNext: Boolean,
    val totalCount: Long
)

data class SocialCommentCursor(
    val bundleId: Long,
    val parentId: Long?,
    val currentBunchId: Long?,
    val createdAt: Instant,
    val id: Long
)
