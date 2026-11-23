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
import com.bunch.recommendation.domain.combination.PhotoQuality
import com.bunch.recommendation.domain.combination.PhotoRole
import com.bunch.recommendation.domain.combination.PhotoSubject
import com.bunch.recommendation.domain.combination.RecoBundle
import com.bunch.recommendation.domain.combination.RecoPhoto
import com.bunch.recommendation.domain.combination.RuleTrace
import com.bunch.recommendation.domain.combination.SourceType
import java.time.Instant
import java.time.LocalTime

/**
 * What the tests of the card share: photos of a member at a time, a bundle made of them, and the card that is put on
 * it. The card is chosen from the time, the members and the quality of the photos, so that is all a photo is given.
 */
internal object CardTestSupport {
    const val DAY = "2026-10-19"

    fun shot(
        id: Long,
        time: String?,
        member: Long = 1,
        quality: PhotoQuality? = PhotoQuality.GOOD,
        source: SourceType = SourceType.CAMERA,
        members: List<Long> = listOf(member),
        date: String = DAY,
        uploaded: Map<Long, Instant> = emptyMap(),
        model: String? = null,
        millis: Int? = null,
        size: Pair<Int, Int>? = null,
        subject: PhotoSubject? = null
    ): RecoPhoto = photo(
        id,
        date = date,
        time = time,
        members = members,
        model = model,
        millis = millis,
        size = size,
        analysis = AiAnalysis(subject = subject),
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
        step: Long = 5
    ): List<RecoPhoto> = (0 until count).map {
        shot(firstId + it, at(it, start, step), members[it % members.size])
    }

    fun bundle(day: DayPreparation.Ready): RecoBundle {
        val eligibility = BundleEligibility()
        val instants = day.photos.mapNotNull { it.instant }
        val photoCount = eligibility.photoCount(day.photos, day.duplicateGroups)
        val uploaderCount = eligibility.uploaderCount(day.photos, day.duplicateGroups)
        return RecoBundle(
            dayKey = day.dayKey,
            kind = BundleKind.GENERAL,
            eventType = null,
            establishedBy = null,
            activity = null,
            subIndex = null,
            photos = day.photos.map { RecoBundle.BundlePhoto(it.photoId, PhotoRole.GENERAL) },
            coverPhotoId = null,
            startAt = instants.min(),
            endAt = instants.max(),
            photoCount = photoCount,
            uploaderCount = uploaderCount,
            hasGroupPhoto = false,
            trace = RuleTrace(
                day.dayKey,
                BundleKind.GENERAL,
                RuleTrace.Counts(photoCount, uploaderCount),
                "reco-cfg-1"
            )
        )
    }

    /** The card put on a bundle made of [photos]. */
    fun select(photos: List<RecoPhoto>, settings: CombinationSettings = CombinationSettings()): RecoBundle {
        val day = ready(photos)
        return CardSelector(settings).select(bundle(day), day)
    }

    /** The photos on the card, in the order of the card. */
    fun onCard(bundle: RecoBundle): List<Long> =
        bundle.photos.filter { it.cardPosition != null }.sortedBy { it.cardPosition }.map { it.photoId }

    /** The photos as pictures of a card, each counted for its first member and taken by that member. */
    fun candidates(photos: List<RecoPhoto>): List<CardCandidate> = ready(photos).photos.map {
        val member = it.photo.uploads.first().memberId
        CardCandidate(photo = it, shareMember = member, taker = member)
    }

    fun ids(photos: List<RecoPhoto>): List<Long> = photos.map { it.photoId }
}
