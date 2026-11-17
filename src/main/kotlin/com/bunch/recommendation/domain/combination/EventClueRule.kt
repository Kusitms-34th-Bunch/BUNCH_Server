package com.bunch.recommendation.domain.combination

/**
 * The clues of one event and how much each is worth (recommended bundle specification 3-3). A [strong] clue shows the
 * event almost surely, but one photo is still confirmed by another sign (6-1). A [support] clue alone is not enough:
 * two kinds of them are. A [weak] clue is common outside the event too, so it only counts after a support clue.
 * A clue is in one of the three at most.
 */
data class EventClueRule(
    val strong: Set<EventClueCode> = emptySet(),
    val support: Set<EventClueCode> = emptySet(),
    val weak: Set<EventClueCode> = emptySet()
) {
    init {
        val all = strong.size + support.size + weak.size
        require((strong + support + weak).size == all) {
            "a clue is strong, support or weak for an event, not two of them"
        }
    }
}
