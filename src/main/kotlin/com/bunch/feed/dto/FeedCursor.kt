package com.bunch.feed.dto

import com.bunch.feed.domain.FeedSegment
import java.time.Instant

data class FeedCursor(
    val version: Int = 1,
    val anchorTime: Instant,
    val segment: FeedSegment,
    val lastTime: Instant?,
    val lastId: Long?,
    val viewerId: Long,
    val currentBunchId: Long?,
    val membershipContext: String,
    val followingContext: String,
    val followingSeen: Boolean = false,
    val bundleCount: Long = 0,
    val lastAdId: Long? = null
)
