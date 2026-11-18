package com.bunch.recommendation.domain.combination

import kotlin.math.floor

/**
 * Chooses the pictures of the card out of the pictures of a bundle that are more than the card holds (recommended
 * bundle specification 8-2, 8-3). The rules come in an order, and a rule above wins over the ones below it: when a rule
 * cannot be kept for a picture, the rules below it are the ones given up. Each step takes pictures only while the card
 * has a free place, so the card never holds more than [size] pictures.
 *
 * 1. A clue (3rd rule): an event bundle takes one clue picture, one with a person first. A general bundle takes one
 *    picture of an event that was not a bundle (8-2).
 * 2. The members (4th rule): every member who has a picture gets one, the member with the fewest pictures first (photo
 *    box 4.2.8 rule 2). The limit of the specification of 2 less than the card does not apply, because a bunch has
 *    at most 8 members and a card 10 pictures.
 * 3. The make-up (5th rule): one picture of 3 people or more, and one in each quarter of the time.
 * 4. The rest (6th rule): by the score. The limits are a clue in at most half of the card, a member in at most 60%, a
 *    subject that is not a person in at most half. The limit of a member is kept only while the pictures of other
 *    members are left. A limit is given up when no picture can be put with it, the one of the subject first, then the
 *    one of the member, then the one of the clue, and the rules given up are told.
 *
 * The score does not tell two pictures apart when it is the same; the earlier picture, then the lower photo number goes
 * first.
 */
class CardPicker(
    private val settings: CombinationSettings = CombinationSettings(),
    private val scorer: CardScorer = CardScorer(settings),
    private val quarters: CardQuarters = CardQuarters(settings)
) {
    /** The pictures chosen, why each was chosen, and the limits that were given up, as text for the trace. */
    data class Choice(
        val chosen: List<CardCandidate>,
        val picks: List<RuleTrace.Pick>,
        val relaxed: List<String>
    )

    fun pick(pool: List<CardCandidate>, size: Int, event: Boolean): Choice {
        val run = Run(pool, size, event)
        run.clue()
        run.members()
        run.composition()
        run.rest()
        return Choice(run.chosen, run.picks, run.relaxed.toList())
    }

    private inner class Run(
        private val pool: List<CardCandidate>,
        private val size: Int,
        private val event: Boolean
    ) {
        val chosen = mutableListOf<CardCandidate>()
        val picks = mutableListOf<RuleTrace.Pick>()
        val relaxed = linkedSetOf<String>()

        private val chosenIds = mutableSetOf<Long>()
        private val clueLimit = limit(settings.clueMaxRatio)
        private val memberLimit = limit(settings.memberMaxRatio)
        private val subjectLimit = limit(settings.subjectMaxRatio)
        private val place = pool.withIndex().associate { it.value.photoId to it.index }

        fun clue() {
            val clues = left().filter { it.clue }
            if (full() || clues.isEmpty()) return
            if (event) {
                add(best(clues.filter { it.person }.ifEmpty { clues }), CLUE)
            } else {
                add(best(clues), FAILED_CLUE)
            }
        }

        fun members() {
            val byMember = pool.groupBy { it.shareMember }
            val fewestFirst = byMember.entries.sortedWith(
                compareBy({ it.value.size }, { place.getValue(it.value.first().photoId) })
            )
            for ((member, candidates) in fewestFirst) {
                if (full()) return
                if (chosen.any { it.shareMember == member }) continue
                val options = open(candidates, CLUE_LIMIT)
                if (options.isEmpty()) relaxed += "member_guarantee:$member" else add(best(options), MEMBER)
            }
        }

        fun composition() {
            if (!full() && chosen.none { it.group }) {
                val groups = left().filter { it.group }
                val options = open(groups, MEMBER_LIMIT)
                if (groups.isNotEmpty() && options.isEmpty()) relaxed += "group_photo"
                if (options.isNotEmpty()) add(best(options), GROUP)
            }
            quarters.of(pool).forEachIndexed { index, quarter ->
                if (full() || quarter.isEmpty() || quarter.any { it.photoId in chosenIds }) return@forEachIndexed
                val options = open(quarter, MEMBER_LIMIT)
                if (options.isEmpty()) relaxed += "time_part:${index + 1}" else add(best(options), TIME)
            }
        }

        fun rest() {
            while (!full()) {
                val candidates = left()
                if (candidates.isEmpty()) return
                val level = TIERS.first { tier -> open(candidates, tier).isNotEmpty() }
                if (level < SUBJECT_LIMIT) relaxed += "subject_cap"
                if (level < MEMBER_LIMIT) relaxed += "member_cap"
                if (level < CLUE_LIMIT) relaxed += "clue_cap"
                add(best(open(candidates, level)), SCORE)
            }
        }

        // The candidates that are not chosen yet and that the limits of the level allow.
        private fun open(from: Collection<CardCandidate>, level: Int): List<CardCandidate> =
            from.filter { it.photoId !in chosenIds && allowed(it, level) }

        // The limits of the level and of the levels above it (8-3: "the limits from the rule of this level up").
        private fun allowed(candidate: CardCandidate, level: Int): Boolean =
            (level < CLUE_LIMIT || withinClueLimit(candidate)) &&
                (level < MEMBER_LIMIT || withinMemberLimit(candidate)) &&
                (level < SUBJECT_LIMIT || withinSubjectLimit(candidate))

        private fun withinClueLimit(candidate: CardCandidate): Boolean =
            !(event && candidate.clue) || chosen.count { it.clue } < clueLimit

        private fun withinMemberLimit(candidate: CardCandidate): Boolean =
            chosen.count { it.shareMember == candidate.shareMember } < memberLimit ||
                left().none { it.shareMember != candidate.shareMember }

        private fun withinSubjectLimit(candidate: CardCandidate): Boolean {
            val subject = candidate.subject
            val sameSubject = chosen.count { it.subject == subject }
            return subject == null || subject == PhotoSubject.PERSON || sameSubject < subjectLimit
        }

        private fun best(candidates: List<CardCandidate>): CardCandidate = candidates
            .map { it to scorer.score(it, chosen) }
            .sortedWith(
                compareByDescending<Pair<CardCandidate, Double>> { it.second }.thenBy(CardCandidate.byTime) { it.first }
            )
            .first().first

        private fun add(candidate: CardCandidate, by: String) {
            chosen += candidate
            chosenIds += candidate.photoId
            picks += RuleTrace.Pick(candidate.photoId, by)
        }

        private fun left(): List<CardCandidate> = pool.filter { it.photoId !in chosenIds }

        private fun full(): Boolean = chosen.size >= size

        private fun limit(ratio: Double): Int = floor(ratio * size + EPSILON).toInt()
    }

    private companion object {
        // The levels of the 3rd to the 5th rule, from the one that keeps the most limits to the one that keeps none.
        const val SUBJECT_LIMIT = 5
        const val MEMBER_LIMIT = 4
        const val CLUE_LIMIT = 3
        val TIERS = listOf(SUBJECT_LIMIT, MEMBER_LIMIT, CLUE_LIMIT, 0)

        const val CLUE = "P3_clue"
        const val FAILED_CLUE = "P3_failed_clue"
        const val MEMBER = "P4_member"
        const val GROUP = "P5_group"
        const val TIME = "P5_time"
        const val SCORE = "P6_score"
        const val EPSILON = 1e-9
    }
}
