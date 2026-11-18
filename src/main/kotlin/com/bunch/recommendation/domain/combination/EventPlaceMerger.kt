package com.bunch.recommendation.domain.combination

import java.time.Duration

/**
 * Keeps one event of the events that are in the same place (recommended bundle specification 6-2). Two events are in
 * the same place when their cores overlap or are less than [CombinationSettings.eventSamePlaceGap] apart: for the user
 * it is one gathering, and one event with the name of the one that has the most clues is better than two bundles of it.
 * Events that follow each other are one place as far as the chain goes.
 *
 * Of such events the one with the most clue photos (counted by their groups of duplicates) stays, then one that stood
 * by a strong clue, then the one that began first. The others are gone: their clue photos go back to the photos that no
 * event has, and may still be photos of the one that stays (6-4). The one that stays tells which events it took in and
 * why.
 */
class EventPlaceMerger(
    private val settings: CombinationSettings = CombinationSettings()
) {
    fun merge(candidates: List<EventCandidate>, day: DayPreparation.Ready): List<EventCandidate> {
        val groupOf = day.duplicateGroups.flatMap { group -> group.photoIds.map { it to group.id } }.toMap()
        val sorted = candidates.sortedWith(compareBy({ it.core.start }, { it.type }))
        val parent = IntArray(sorted.size) { it }
        for (first in sorted.indices) {
            for (second in first + 1 until sorted.size) {
                if (samePlace(sorted[first], sorted[second])) parent[find(parent, second)] = find(parent, first)
            }
        }
        return sorted.indices.groupBy { find(parent, it) }.values
            .map { members -> keep(members.map { sorted[it] }, groupOf) }
            .sortedWith(compareBy({ it.core.start }, { it.type }))
    }

    private fun keep(events: List<EventCandidate>, groupOf: Map<Long, Long>): EventCandidate {
        val winner = events.sortedWith(
            compareByDescending<EventCandidate> { clueGroups(it, groupOf) }
                .thenBy { if (it.establishedBy == EstablishedBy.STRONG) 0 else 1 }
                .thenBy { it.core.start }
                .thenBy { it.type }
        ).first()
        val taken = events.filter { it !== winner }.map { RuleTrace.MergedEvent(it.type, reason(winner, it)) }
        return winner.copy(mergedEvents = winner.mergedEvents + taken)
    }

    // How many groups of duplicates the clue photos of an event are in: the clue photos as they are counted.
    private fun clueGroups(event: EventCandidate, groupOf: Map<Long, Long>): Int =
        event.cluePhotos.map { groupOf[it.photoId] }.distinct().size

    private fun samePlace(first: EventCandidate, second: EventCandidate): Boolean =
        overlap(first, second) || gap(first, second) < settings.eventSamePlaceGap

    private fun overlap(first: EventCandidate, second: EventCandidate): Boolean =
        !first.core.end.isBefore(second.core.start) && !second.core.end.isBefore(first.core.start)

    private fun gap(first: EventCandidate, second: EventCandidate): Duration = when {
        overlap(first, second) -> Duration.ZERO
        first.core.end.isBefore(second.core.start) -> Duration.between(first.core.end, second.core.start)
        else -> Duration.between(second.core.end, first.core.start)
    }

    private fun reason(winner: EventCandidate, taken: EventCandidate): String =
        if (overlap(winner, taken)) "core_overlap" else "same_place_gap_${gap(winner, taken).toMinutes()}m"

    private fun find(parent: IntArray, index: Int): Int {
        var root = index
        while (parent[root] != root) root = parent[root]
        return root
    }
}
