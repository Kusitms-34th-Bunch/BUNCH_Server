package com.bunch.recommendation.domain

/**
 * What a recalculation does to the stored candidates (4.2.9 rule 1). [kept] are the candidates that stay, with the
 * cluster they are now: their title is not changed and their card photos are chosen again. [created] are the
 * clusters that become a new candidate with a new title, in the order they were given. [deletedCandidateIds] are the
 * candidates that end: split, merged, no longer recommended, or without any photo left.
 */
data class CandidateMatch(
    val kept: Map<Long, PhotoCluster>,
    val created: List<PhotoCluster>,
    val deletedCandidateIds: Set<Long>
)
