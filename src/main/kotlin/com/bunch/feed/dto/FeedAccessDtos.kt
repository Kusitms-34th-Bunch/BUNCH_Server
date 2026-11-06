package com.bunch.feed.dto

import com.bunch.bunch.domain.BunchMembership
import com.bunch.bundle.domain.Bundle

data class FeedViewer(
    val memberId: Long,
    val currentBunchId: Long?,
    val activeBunchIds: Set<Long>
)

data class FeedBundleInteraction(
    val bundle: Bundle,
    val actor: BunchMembership
)
