package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.EventTestSupport.ready
import com.bunch.recommendation.combination.PreparationTestSupport.photo
import com.bunch.recommendation.domain.combination.AiAnalysis
import com.bunch.recommendation.domain.combination.BundleEligibility
import com.bunch.recommendation.domain.combination.BundleKind
import com.bunch.recommendation.domain.combination.CardCandidate
import com.bunch.recommendation.domain.combination.CardSelector
import com.bunch.recommendation.domain.combination.CombinationSettings
import com.bunch.recommendation.domain.combination.DayPreparation
import com.bunch.recommendation.domain.combination.EstablishedBy
import com.bunch.recommendation.domain.combination.EventClueCode
import com.bunch.recommendation.domain.combination.EventType
import com.bunch.recommendation.domain.combination.PeopleCount
import com.bunch.recommendation.domain.combination.PhotoQuality
import com.bunch.recommendation.domain.combination.PhotoRole
import com.bunch.recommendation.domain.combination.PhotoSubject
import com.bunch.recommendation.domain.combination.Reading
import com.bunch.recommendation.domain.combination.RecoBundle
import com.bunch.recommendation.domain.combination.RecoPhoto
import com.bunch.recommendation.domain.combination.RuleTrace
import com.bunch.recommendation.domain.combination.SourceType
import java.time.Instant
import java.time.LocalTime

/**
 * What the tests of the card share: photos with the subject, the people and the clue the AI would have told, a bundle
 * made of them, and the card that is put on it. A photo has no fingerprint unless it is given one, so that the pictures
 * are not alike and the score of a picture is its quality.
 */
internal object CardTestSupport {
    const val DAY = "2026-10-19"

    fun shot(
        id: Long,
        time: String?,
        member: Long = 1,
        subject: PhotoSubject? = null,
        people: PeopleCount? = null,
        quality: PhotoQuality? = PhotoQuality.GOOD,
        clue: EventClueCode? = null,
        source: SourceType = SourceType.CAMERA,
        fingerprint: Long? = null,
        members: List<Long> = listOf(member),
        date: String = DAY,
        uploaded: Map<Long, Instant> = emptyMap(),
        model: String? = null,
        millis: Int? = null,
        size: Pair<Int, Int>? = null
    ): RecoPhoto = photo(
        id,
        date = date,
        time = time,
        members = members,
        fingerprint = fingerprint,
        model = model,
        millis = millis,
        size = size,
        analysis = AiAnalysis(
            clues = listOfNotNull(clue?.let { Reading(it, 0.9) }),
            subject = subject,
            people = people
        ),
        memberUploadedAt = uploaded
    ).copy(quality = quality, source = source)

    /** The time of the photo [index] of a run that starts at [start], one every [step] minutes. */
    fun at(index: Int, start: String = "09:00", step: Long = 5): String =
        LocalTime.parse(start).plusMinutes(step * index).toString()

    /** [count] photos from [firstId], one every [step] minutes, uploaded by the [members] in turn. */
    fun run(
        firstId: Long,
        count: Int,
        members: List<Long> = listOf(1, 2),
        start: String = "09:00",
        step: Long = 5,
        subject: PhotoSubject? = null,
        people: PeopleCount? = null
    ): List<RecoPhoto> = (0 until count).map {
        shot(firstId + it, at(it, start, step), members[it % members.size], subject, people)
    }

    fun bundle(
        day: DayPreparation.Ready,
        kind: BundleKind = BundleKind.GENERAL,
        clues: Set<Long> = emptySet()
    ): RecoBundle {
        val eligibility = BundleEligibility()
        val event = kind == BundleKind.EVENT
        val instants = day.photos.mapNotNull { it.instant }
        val photoCount = eligibility.photoCount(day.photos, day.duplicateGroups)
        val uploaderCount = eligibility.uploaderCount(day.photos, day.duplicateGroups)
        return RecoBundle(
            dayKey = day.dayKey,
            kind = kind,
            eventType = if (event) EventType.BIRTHDAY else null,
            establishedBy = if (event) EstablishedBy.STRONG else null,
            activity = null,
            subIndex = null,
            photos = day.photos.map {
                val role = when {
                    it.photoId in clues -> PhotoRole.CLUE
                    event -> PhotoRole.RELATED
                    else -> PhotoRole.GENERAL
                }
                RecoBundle.BundlePhoto(it.photoId, role)
            },
            coverPhotoId = null,
            startAt = instants.min(),
            endAt = instants.max(),
            photoCount = photoCount,
            uploaderCount = uploaderCount,
            hasGroupPhoto = false,
            trace = RuleTrace(day.dayKey, kind, RuleTrace.Counts(photoCount, uploaderCount), "reco-cfg-1")
        )
    }

    /** The card put on a bundle made of [photos]. */
    fun select(
        photos: List<RecoPhoto>,
        kind: BundleKind = BundleKind.GENERAL,
        clues: Set<Long> = emptySet(),
        failed: Set<Long> = emptySet(),
        settings: CombinationSettings = CombinationSettings()
    ): RecoBundle {
        val day = ready(photos)
        return CardSelector(settings).select(bundle(day, kind, clues), day, failed)
    }

    /** The photos on the card, in the order of the card. */
    fun onCard(bundle: RecoBundle): List<Long> =
        bundle.photos.filter { it.cardPosition != null }.sortedBy { it.cardPosition }.map { it.photoId }

    fun candidates(photos: List<RecoPhoto>): List<CardCandidate> = ready(photos).photos.map {
        CardCandidate(
            photo = it,
            shareMember = it.photo.uploads.first().memberId,
            clue = false,
            person = false,
            group = false,
            subject = null
        )
    }

    fun ids(photos: List<RecoPhoto>): List<Long> = photos.map { it.photoId }
}
