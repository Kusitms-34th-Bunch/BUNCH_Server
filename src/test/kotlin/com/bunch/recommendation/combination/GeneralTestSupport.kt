package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.EventTestSupport.ready
import com.bunch.recommendation.combination.PreparationTestSupport.photo
import com.bunch.recommendation.domain.combination.ActivityCode
import com.bunch.recommendation.domain.combination.AiAnalysis
import com.bunch.recommendation.domain.combination.BundleEligibility
import com.bunch.recommendation.domain.combination.DayPreparation
import com.bunch.recommendation.domain.combination.EventResult
import com.bunch.recommendation.domain.combination.GeneralActivitySections
import com.bunch.recommendation.domain.combination.GeneralBundleFinder
import com.bunch.recommendation.domain.combination.GeneralCounter
import com.bunch.recommendation.domain.combination.GeneralResult
import com.bunch.recommendation.domain.combination.GeneralSection
import com.bunch.recommendation.domain.combination.GeneralSegment
import com.bunch.recommendation.domain.combination.GeneralSegmenter
import com.bunch.recommendation.domain.combination.PeopleCount
import com.bunch.recommendation.domain.combination.PhotoSubject
import com.bunch.recommendation.domain.combination.Reading
import com.bunch.recommendation.domain.combination.RecoPhoto
import java.time.LocalTime

/** What the tests of the general bundles share: photos of an activity, and the segments and the sections. */
internal object GeneralTestSupport {
    const val DAY = "2026-10-19"

    /**
     * Photos that show [code] (no analysis at all when it is null, so the activity is not known), one every [step]
     * minutes from [from], uploaded by the [members] in turn. They are of food, so no person photo of an event.
     */
    fun active(
        firstId: Long,
        from: String,
        count: Int,
        code: ActivityCode?,
        members: List<Long> = listOf(1, 2),
        step: Long = 10,
        date: String = DAY,
        confidence: Double = 0.9
    ): List<RecoPhoto> = (0 until count).map { index ->
        photo(
            firstId + index,
            date = date,
            time = LocalTime.parse(from).plusMinutes(step * index).toString(),
            members = listOf(members[index % members.size]),
            analysis = if (code == null) {
                AiAnalysis.NONE
            } else {
                AiAnalysis(
                    activity = Reading(code, confidence),
                    subject = PhotoSubject.FOOD,
                    people = PeopleCount.P0
                )
            }
        )
    }

    /** The day of the photos, made ready by the preparation of the days. */
    fun day(photos: List<RecoPhoto>): DayPreparation.Ready = ready(photos)

    fun counter(day: DayPreparation.Ready) = GeneralCounter(BundleEligibility(), day.duplicateGroups)

    /** The segments of the photos of a day, as the segmenter cuts them. */
    fun segments(photos: List<RecoPhoto>): List<GeneralSegment> {
        val ready = day(photos)
        return GeneralSegmenter().segment(ready.photos, counter(ready))
    }

    /** The sections of the photos of a day, as the fifth rule makes them out of the segments. */
    fun sections(photos: List<RecoPhoto>): List<GeneralSection> = GeneralActivitySections().build(segments(photos))

    fun find(photos: List<RecoPhoto>, events: EventResult = EventResult(emptyList(), emptyList())): GeneralResult =
        GeneralBundleFinder().find(day(photos), events)

    fun ids(photos: List<RecoPhoto>): List<Long> = photos.map { it.photoId }
}
