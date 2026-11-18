package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.EventTestSupport.ready
import com.bunch.recommendation.combination.PreparationTestSupport.photo
import com.bunch.recommendation.domain.combination.ActivityCode
import com.bunch.recommendation.domain.combination.AiAnalysis
import com.bunch.recommendation.domain.combination.AiStatus
import com.bunch.recommendation.domain.combination.BundleCombiner
import com.bunch.recommendation.domain.combination.BundleKind
import com.bunch.recommendation.domain.combination.CombinationResult
import com.bunch.recommendation.domain.combination.DayCombination
import com.bunch.recommendation.domain.combination.DayPreparation
import com.bunch.recommendation.domain.combination.EventClueCode
import com.bunch.recommendation.domain.combination.GeneralBundleFinder
import com.bunch.recommendation.domain.combination.PeopleCount
import com.bunch.recommendation.domain.combination.PhotoQuality
import com.bunch.recommendation.domain.combination.PhotoSubject
import com.bunch.recommendation.domain.combination.Reading
import com.bunch.recommendation.domain.combination.RecoBundle
import com.bunch.recommendation.domain.combination.RecoPhoto
import com.bunch.recommendation.domain.combination.SensitiveClueCode
import com.bunch.recommendation.domain.combination.SourceType
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * What the tests of the assembly share: photos with what the AI would have told, a day of them made ready, and the
 * bundles the combination makes of them.
 */
internal object CombinerTestSupport {
    const val DAY = "2026-10-19"

    fun shot(
        id: Long,
        time: String?,
        member: Long = 1,
        date: String? = DAY,
        activity: ActivityCode? = null,
        clue: EventClueCode? = null,
        subject: PhotoSubject? = null,
        people: PeopleCount? = null,
        sensitive: SensitiveClueCode? = null,
        aiStatus: AiStatus = AiStatus.DONE,
        fingerprint: Long? = null,
        offset: ZoneOffset? = null,
        batch: UUID? = null,
        quality: PhotoQuality? = PhotoQuality.GOOD,
        source: SourceType = SourceType.CAMERA
    ): RecoPhoto = photo(
        id,
        date = date,
        time = time,
        members = listOf(member),
        fingerprint = fingerprint,
        offset = offset,
        aiStatus = aiStatus,
        analysis = if (aiStatus != AiStatus.DONE) {
            AiAnalysis.NONE
        } else {
            AiAnalysis(
                clues = listOfNotNull(clue?.let { Reading(it, 0.9) }),
                sensitiveClues = listOfNotNull(sensitive?.let { Reading(it, 0.9) }),
                activity = activity?.let { Reading(it, 0.9) },
                subject = subject,
                people = people
            )
        },
        batch = batch
    ).copy(quality = quality, source = source)

    /** [count] photos of [activity] from [firstId], one every [step] minutes, uploaded by the members in turn. */
    fun meals(
        firstId: Long,
        from: String,
        count: Int,
        activity: ActivityCode? = ActivityCode.MEAL,
        members: List<Long> = listOf(1, 2),
        step: Long = 10,
        date: String = DAY,
        batch: (Int) -> UUID? = { null }
    ): List<RecoPhoto> = (0 until count).map {
        shot(
            firstId + it,
            LocalTime.parse(from).plusMinutes(step * it).toString(),
            members[it % members.size],
            date = date,
            activity = activity,
            batch = batch(it)
        )
    }

    fun combine(photos: List<RecoPhoto>): CombinationResult = BundleCombiner().combine(photos)

    fun bundles(photos: List<RecoPhoto>): List<RecoBundle> =
        (combine(photos).days.single() as DayCombination.Bundled).bundles

    fun day(photos: List<RecoPhoto>): DayPreparation.Ready = ready(photos)

    /** The general bundles of the day of the photos, before anything is given to them. */
    fun generals(photos: List<RecoPhoto>): List<RecoBundle> = GeneralBundleFinder().find(day(photos)).bundles

    fun photoIds(bundle: RecoBundle) = bundle.photos.map { it.photoId }

    fun kinds(bundles: List<RecoBundle>) = bundles.map { it.kind }

    fun onCard(bundle: RecoBundle): List<Long> =
        bundle.photos.filter { it.cardPosition != null }.sortedBy { it.cardPosition }.map { it.photoId }

    val EVENT = BundleKind.EVENT
}
