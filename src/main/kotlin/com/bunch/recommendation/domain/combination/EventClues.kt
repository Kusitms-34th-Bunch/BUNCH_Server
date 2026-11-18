package com.bunch.recommendation.domain.combination

/**
 * What an event reads of a photo (recommended bundle specification 3-1, 4-3, 5-1). A clue counts when the AI is sure
 * enough ([CombinationSettings.clueMinConfidence]); the limit is applied here and not when the input is built (4-3).
 *
 * A clue photo is a photo that shows a clue, was taken with the camera and has a time (a time that was borrowed is
 * one). Its quality and whether it is a document are not looked at: a blurred photo of the cake still tells that there
 * was a cake (3-1, 5-1). A person photo is one of which the subject is a person and the count is one or more (6-1,
 * 6-4).
 */
class EventClues(
    private val settings: CombinationSettings = CombinationSettings(),
    private val mapping: EventClueMapping = EventClueMapping.DEFAULT
) {
    /** The clues the AI saw in the photo with enough confidence. */
    fun seen(photo: PreparedPhoto): Set<EventClueCode> =
        photo.photo.analysis.clues.filter { it.confidence >= settings.clueMinConfidence }.map { it.code }.toSet()

    fun isCluePhoto(photo: PreparedPhoto): Boolean =
        photo.photo.source == SourceType.CAMERA && photo.instant != null && seen(photo).isNotEmpty()

    /** All the clues of an event, strong, support and weak. */
    fun codesOf(type: EventType): Set<EventClueCode> {
        val rule = mapping.ruleOf(type)
        return rule.strong + rule.support + rule.weak
    }

    fun isCluePhotoOf(photo: PreparedPhoto, type: EventType): Boolean =
        isCluePhoto(photo) && seen(photo).any { it in codesOf(type) }

    fun isPersonPhoto(photo: PreparedPhoto): Boolean =
        photo.photo.analysis.subject == PhotoSubject.PERSON &&
            (photo.photo.analysis.people ?: PeopleCount.P0) >= PeopleCount.P1
}
