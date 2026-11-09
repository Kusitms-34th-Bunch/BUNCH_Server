package com.bunch.recommendation.domain

/**
 * Compares the clusters made again with the stored candidates (4.2.9 rule 1).
 *
 * A candidate stays when exactly one cluster still has some of its photos, that cluster has the photos of no other
 * candidate, and the cluster is still recommended. A candidate that is split over several clusters or merged with
 * another candidate ends, and every recommended cluster that is not kept becomes a new candidate. A candidate with no
 * photo left in any cluster, or whose cluster is no longer recommended, ends too.
 *
 * Only the stored candidates can be told apart. A cluster that was not recommended is not stored, so a candidate that
 * grows into it is not seen as a merge and stays.
 */
class CandidateMatcher {
    /**
     * [existing] maps a candidate to all of its photos, also the ones its card does not show. [clusters] are all the
     * clusters of the recalculated dates, recommended or not. [isRecommended] is the final check of a cluster, made
     * after the burst photos were removed.
     */
    fun match(
        existing: Map<Long, Set<Long>>,
        clusters: List<PhotoCluster>,
        isRecommended: (PhotoCluster) -> Boolean
    ): CandidateMatch {
        val recommended = clusters.map(isRecommended)
        val sharing = clustersSharingPhotos(existing, clusters)
        val kept = keptCandidates(sharing, recommended)
        val created = clusters.filterIndexed { index, _ ->
            recommended[index] && index !in kept.values
        }

        return CandidateMatch(
            kept = kept.mapValues { (_, index) -> clusters[index] },
            created = created,
            deletedCandidateIds = existing.keys - kept.keys
        )
    }

    // For each candidate, the positions of the clusters that have at least one of its photos.
    private fun clustersSharingPhotos(
        existing: Map<Long, Set<Long>>,
        clusters: List<PhotoCluster>
    ): Map<Long, List<Int>> = existing.mapValues { (_, photoIds) ->
        clusters.indices.filter { index -> clusters[index].photoIds.any { it in photoIds } }
    }

    // The candidates that stay and the position of the cluster each one is now.
    private fun keptCandidates(
        sharing: Map<Long, List<Int>>,
        recommended: List<Boolean>
    ): Map<Long, Int> {
        val kept = mutableMapOf<Long, Int>()
        sharing.forEach { (candidateId, indexes) ->
            val index = indexes.singleOrNull() ?: return@forEach
            val candidatesOfCluster = sharing.values.count { index in it }
            if (candidatesOfCluster == 1 && recommended[index]) kept[candidateId] = index
        }
        return kept
    }
}
