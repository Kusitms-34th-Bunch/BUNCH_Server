package com.bunch.recommendation.domain.combination

/**
 * Makes the bundle of an event that stood (recommended bundle specification 4-5, 6-4, 11). The event step tells which
 * photos belong to it; this gives the bundle its values: the photos with their roles (a photo that shows a clue of the
 * event is a clue, the others of the bundle are related), the pictures and members counted as the specification counts
 * them, the time of its first and of its last photo, and the part of the trace that tells how the event stood.
 */
class EventBundleBuilder(
    private val facts: BundleFacts = BundleFacts()
) {
    fun build(day: DayPreparation.Ready, event: EventResult.Confirmed): RecoBundle {
        val related = event.relatedPhotoIds.toSet()
        val clues = event.cluePhotoIds.toSet()
        return facts.build(
            day = day,
            kind = BundleKind.EVENT,
            photos = day.photos.filter { it.photoId in related },
            roleOf = { if (it.photoId in clues) PhotoRole.CLUE else PhotoRole.RELATED },
            eventType = event.type,
            establishedBy = event.establishedBy,
            event = event.trace()
        )
    }
}
