package com.bunch.recommendation.domain.combination

import java.time.Duration

/**
 * How many members took photos around each picture (recommended bundle specification 4, fifth rule second step and the
 * cover): the members who took a picture at most [window] before or after it, the member of the picture itself
 * counted. A moment in which many members took out their cameras is a moment that mattered to the gathering (the cake,
 * the group photo, the highlight of a show). A picture with no time has no moment around it and counts 0.
 */
class MomentMembers(
    private val window: Duration
) {
    fun of(pool: List<CardCandidate>): Map<Long, Int> {
        val timed = pool.filter { it.photo.instant != null }
        return timed.associate { picture ->
            val at = picture.photo.instant!!
            val around = timed.filter { Duration.between(at, it.photo.instant!!).abs() <= window }
            picture.photoId to around.map { it.taker }.toSet().size
        }
    }
}
