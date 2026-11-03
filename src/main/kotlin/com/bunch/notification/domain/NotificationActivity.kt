package com.bunch.notification.domain

// Only snapshots and totals live in the notification; successful business replay stays with the producer.
data class NotificationActivity(
    val actors: Map<Long, String>,
    val photoCount: Int,
    val firstActorId: Long = actors.keys.first()
) {
    fun added(actorId: Long, nickname: String, count: Int) = NotificationActivity(
        actors = actors + (actorId to (actors[actorId] ?: nickname)),
        photoCount = Math.addExact(photoCount, count),
        firstActorId = firstActorId
    )

    fun body(): String {
        // jsonb object key order is not insertion order; keep the first actor explicitly.
        val first = actors.getValue(firstActorId)
        val actor = if (actors.size == 1) "${first}님이" else "${first}님 외 ${actors.size - 1}명이"
        return "$actor 사진 ${photoCount}장을 추가했어요."
    }
}
