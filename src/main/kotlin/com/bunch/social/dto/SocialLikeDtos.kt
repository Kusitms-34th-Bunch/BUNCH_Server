package com.bunch.social.dto

data class SocialLikeSummary(
    val likeCount: Long = 0,
    val likedByCurrentBunch: Boolean = false
)

data class SocialLikeResponse(
    val bundleId: Long,
    val currentBunchId: Long?,
    val likedByCurrentBunch: Boolean,
    val likeCount: Long
)
