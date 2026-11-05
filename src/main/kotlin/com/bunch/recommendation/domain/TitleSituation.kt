package com.bunch.recommendation.domain

/**
 * The situation a recommended bundle title is written for (4.2.2 rule 3). A title is judged for the first situation
 * that fits, from [SPECIAL_DAY] down to [DEFAULT]. [MEMBER_NAMES] is not judged: it is the group of sentences that have
 * the names of the members in them.
 */
enum class TitleSituation {
    SPECIAL_DAY,
    FIRST_GATHERING,
    EVERYONE,
    AFTER_LONG_GAP,
    PAST_MIDNIGHT,
    DEFAULT,
    MEMBER_NAMES
}
