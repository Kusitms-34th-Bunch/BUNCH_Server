package com.bunch.recommendation.domain.combination

import java.time.Duration

/**
 * Finds the events that stand in a day (recommended bundle specification 6-1). For each kind of event the photos that
 * show one of its clues are put in the order of the time and cut into stretches: a day event is one stretch, and the
 * others are cut where two photos are apart by the time that keeps an event together (3-5). A clue and a sign that
 * confirms it are only the ones of the same event and of the same stretch: a bouquet in the morning and balloons at
 * night are not two clues of one party.
 *
 * A stretch makes an event when
 * - a photo with a strong clue has a sign that confirms it (the same clue in a photo of another group of duplicates,
 *   another clue of the event, or a person as the subject), or
 * - there are two kinds of support clues, a weak clue counting only after a support clue and for one kind at most, or
 * - there is one support clue on a day of the year that belongs to the event (the year end and the lunar holidays).
 * One photo does not make an event alone, and the members are not counted here: the bundle is (6-4).
 */
class EventEstablisher(
    private val settings: CombinationSettings = CombinationSettings(),
    private val clues: EventClues = EventClues(settings),
    private val mapping: EventClueMapping = EventClueMapping.DEFAULT,
    private val windows: EventDateWindows = EventDateWindows(settings)
) {
    fun establish(day: DayPreparation.Ready): List<EventCandidate> {
        val groupOf = day.duplicateGroups.flatMap { group -> group.photoIds.map { it to group.id } }.toMap()
        val cluePhotos = day.photos.filter(clues::isCluePhoto)
        return EventType.entries.flatMap { type ->
            val ofType = cluePhotos.filter { clues.isCluePhotoOf(it, type) }
            stretches(type, ofType).mapNotNull { stretch -> established(type, stretch, groupOf, day) }
        }
    }

    private fun stretches(type: EventType, photos: List<PreparedPhoto>): List<List<PreparedPhoto>> {
        if (photos.isEmpty()) return emptyList()
        val together = settings.continuityOf(type.pace) ?: return listOf(photos)
        val stretches = mutableListOf<MutableList<PreparedPhoto>>()
        for (photo in photos) {
            val current = stretches.lastOrNull()
            if (current != null && Duration.between(current.last().instant, photo.instant) < together) {
                current += photo
            } else {
                stretches += mutableListOf(photo)
            }
        }
        return stretches
    }

    private fun established(
        type: EventType,
        stretch: List<PreparedPhoto>,
        groupOf: Map<Long, Long>,
        day: DayPreparation.Ready
    ): EventCandidate? {
        val rule = mapping.ruleOf(type)
        val seen = stretch.flatMap { clues.seen(it) }.toSet()
        val strongPhotos = stretch.filter { photo -> clues.seen(photo).any { it in rule.strong } }
        val supportKinds = EventClueCode.entries.filter { it in rule.support && it in seen }
        val weakKinds = EventClueCode.entries.filter { it in rule.weak && it in seen }
        val kinds = supportKinds.size + if (supportKinds.isNotEmpty()) minOf(1, weakKinds.size) else 0

        val signals = strongPhotos.map { signalsOf(it, rule, stretch, supportKinds, weakKinds, groupOf) }
        val confirmed = signals.filter { it.isNotEmpty() }
        val establishedBy = when {
            confirmed.isNotEmpty() -> EstablishedBy.STRONG
            kinds >= 2 -> EstablishedBy.SUPPORT
            (type == EventType.YEAR_END || type == EventType.HOLIDAY) &&
                supportKinds.isNotEmpty() && windows.contains(type, day.dayKey) -> EstablishedBy.DATE_BOOST
            else -> return null
        }
        return EventCandidate(
            type = type,
            establishedBy = establishedBy,
            cluePhotos = stretch,
            strongPhotoIds = strongPhotos.map { it.photoId }.takeIf { establishedBy == EstablishedBy.STRONG }.orEmpty(),
            confirmSignals = confirmed.flatten().distinct().sorted(),
            supportKinds = supportKinds,
            weakKinds = weakKinds,
            core = TimeRange(stretch.first().instant!!, stretch.last().instant!!)
        )
    }

    // The signs that confirm the strong clue of a photo (6-1). They are counted as signs, not as photos.
    private fun signalsOf(
        photo: PreparedPhoto,
        rule: EventClueRule,
        stretch: List<PreparedPhoto>,
        supportKinds: List<EventClueCode>,
        weakKinds: List<EventClueCode>,
        groupOf: Map<Long, Long>
    ): List<RuleTrace.ConfirmSignal> {
        val strongOfPhoto = clues.seen(photo).filter { it in rule.strong }
        return buildList {
            val inAnotherGroup = stretch.any { other ->
                groupOf[other.photoId] != groupOf[photo.photoId] && clues.seen(other).any { it in strongOfPhoto }
            }
            if (inAnotherGroup) add(RuleTrace.ConfirmSignal.SAME_CLUE_IN_OTHER_PHOTO)
            if (supportKinds.size + weakKinds.size >= 1) add(RuleTrace.ConfirmSignal.OTHER_CLUE_KINDS)
            if (clues.isPersonPhoto(photo)) add(RuleTrace.ConfirmSignal.PERSON)
        }
    }
}
