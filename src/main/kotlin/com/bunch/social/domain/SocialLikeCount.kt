package com.bunch.social.domain

interface SocialLikeCount {
    val bundleId: Long
    val likeCount: Long
    val currentBunchLikes: Int
}
