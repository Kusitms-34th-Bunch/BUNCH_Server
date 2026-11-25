package com.bunch.recommendation.domain.combination

import java.time.Duration
import kotlin.math.floor
import kotlin.math.max

/**
 * Chooses the pictures of the card out of the pictures of a bundle that are more than the card holds (recommended
 * bundle specification 4). The rules come in an order, and a rule above wins over the ones below it: when a rule cannot
 * be kept for a picture, the rules below it are the ones given up. Each step takes pictures only while the card has a
 * free place, so the card never holds more than [size] pictures.
 *
 * 1. The members (3rd rule): every member who has a picture gets one, the members with the most pictures first, and at
 *    most 2 less than the card of them. A member has at most [CombinationSettings.memberMaxRatio] of the card, and that
 *    limit is kept only while the pictures of other members are left.
 * 2. The make-up (4th rule): one picture of a together moment (R2), at most half of the card in any
 *    [CombinationSettings.cardWindow] (so that the card does not crowd into one moment), and one picture in each part
 *    of the pictures cut into [CombinationSettings.timeBuckets] by the number, in the order of the time. A rule that no
 *    picture can keep is the only one given up.
 * 3. The rest (5th rule): one picture at a time, preferring one taken [CombinationSettings.cardWindow] or more from all
 *    the ones that are chosen already; then one with more members who took photos around it; then one of good quality.
 *    When no picture is that far from the chosen ones, that preference alone is left out.
 *
 * The picture that stands out in every one of the three is the same for the same pictures: when they are as good, the
 * earlier one, then the one uploaded earlier, then the lower photo number goes first (X9).
 */
class CardPicker(
    private val settings: CombinationSettings = CombinationSettings(),
    private val quarters: CardQuarters = CardQuarters(settings)
) {
    /** The pictures chosen, why each was chosen, and the limits that were given up, as text for the trace. */
    data class Choice(
        val chosen: List<CardCandidate>,
        val picks: List<RuleTrace.Pick>,
        val relaxed: List<String>
    )

    /**
     * [together] are the numbers of the photos that are in a moment the members took photos together (R2), and [around]
     * is how many members took photos around each picture ([MomentMembers]).
     */
    fun pick(pool: List<CardCandidate>, size: Int, together: Set<Long>, around: Map<Long, Int>): Choice {
        val run = Run(pool, size, together, around)
        run.members()
        run.composition()
        run.rest()
        return Choice(run.chosen, run.picks, run.relaxed.toList())
    }

    private inner class Run(
        private val pool: List<CardCandidate>,
        private val size: Int,
        private val together: Set<Long>,
        private val around: Map<Long, Int>
    ) {
        val chosen = mutableListOf<CardCandidate>()
        val picks = mutableListOf<RuleTrace.Pick>()
        val relaxed = linkedSetOf<String>()

        private val chosenIds = mutableSetOf<Long>()
        private val memberLimit = floor(settings.memberMaxRatio * size + EPSILON).toInt()
        private val windowLimit = size / 2
        private val place = pool.withIndex().associate { it.value.photoId to it.index }

        fun members() {
            val byMember = pool.groupBy { it.shareMember }
            val mostPicturesFirst = compareByDescending<Map.Entry<Long, List<CardCandidate>>> { it.value.size }
                .thenBy { place.getValue(it.value.first().photoId) }
            val guaranteed = byMember.entries.sortedWith(mostPicturesFirst).take(max(size - 2, 0))
            for ((member, candidates) in guaranteed) {
                if (full()) return
                if (chosen.any { it.shareMember == member }) continue
                val options = open(candidates, MEMBER_LEVEL)
                if (options.isEmpty()) relaxed += "member_guarantee:$member" else add(prefer(options), MEMBER)
            }
        }

        fun composition() {
            val inMoment = left().filter { it.photoId in together }
            if (!full() && chosen.none { it.photoId in together } && inMoment.isNotEmpty()) {
                val options = open(inMoment, ALL_LEVEL)
                if (options.isEmpty()) relaxed += "together_moment" else add(prefer(options), TOGETHER)
            }
            quarters.of(pool).forEachIndexed { index, quarter ->
                if (full() || quarter.isEmpty() || quarter.any { it.photoId in chosenIds }) return@forEachIndexed
                val options = open(quarter, ALL_LEVEL)
                if (options.isEmpty()) relaxed += "time_part:${index + 1}" else add(prefer(options), TIME)
            }
        }

        fun rest() {
            while (!full()) {
                val candidates = left()
                if (candidates.isEmpty()) return
                val level = TIERS.first { tier -> open(candidates, tier).isNotEmpty() }
                if (level < ALL_LEVEL) relaxed += "window_cap"
                if (level < MEMBER_LEVEL) relaxed += "member_cap"
                add(prefer(open(candidates, level)), REST)
            }
        }

        // The candidates that are not chosen yet and that the limits of the level allow.
        private fun open(from: Collection<CardCandidate>, level: Int): List<CardCandidate> =
            from.filter { it.photoId !in chosenIds && allowed(it, level) }

        // The limits of the level and of the levels above it: the member limit (3rd rule), then the window (4th rule).
        private fun allowed(candidate: CardCandidate, level: Int): Boolean =
            (level < MEMBER_LEVEL || withinMemberLimit(candidate)) &&
                (level < ALL_LEVEL || withinWindowLimit(candidate))

        private fun withinMemberLimit(candidate: CardCandidate): Boolean =
            chosen.count { it.shareMember == candidate.shareMember } < memberLimit ||
                left().none { it.shareMember != candidate.shareMember }

        // No window that holds the candidate holds more than half of the card with it.
        private fun withinWindowLimit(candidate: CardCandidate): Boolean {
            val at = candidate.photo.instant ?: return true
            val times = chosen.mapNotNull { it.photo.instant } + at
            return times.filter { start -> start <= at && at < start.plus(settings.cardWindow) }
                .none { start -> times.count { it >= start && it < start.plus(settings.cardWindow) } > windowLimit }
        }

        // The one the 5th rule takes: far from the chosen, many members around it, good quality, then the same order.
        private fun prefer(candidates: List<CardCandidate>): CardCandidate = candidates.sortedWith(
            compareByDescending<CardCandidate> { isFar(it) }
                .thenByDescending { around[it.photoId] ?: 0 }
                .thenByDescending { it.good }
                .then(CardCandidate.byTimeThenUpload)
        ).first()

        // Taken the window or more from every chosen picture that has a time; a picture with no time is not far.
        private fun isFar(candidate: CardCandidate): Boolean {
            val at = candidate.photo.instant ?: return false
            return chosen.mapNotNull { it.photo.instant }.none { Duration.between(at, it).abs() < settings.cardWindow }
        }

        private fun add(candidate: CardCandidate, by: String) {
            chosen += candidate
            chosenIds += candidate.photoId
            picks += RuleTrace.Pick(candidate.photoId, by)
        }

        private fun left(): List<CardCandidate> = pool.filter { it.photoId !in chosenIds }

        private fun full(): Boolean = chosen.size >= size
    }

    private companion object {
        // The levels from the one that keeps the most limits to the one that keeps none.
        const val ALL_LEVEL = 2
        const val MEMBER_LEVEL = 1
        val TIERS = listOf(ALL_LEVEL, MEMBER_LEVEL, 0)

        const val MEMBER = "P3_member"
        const val TOGETHER = "P4_together"
        const val TIME = "P4_time"
        const val REST = "P5_rest"
        const val EPSILON = 1e-9
    }
}
