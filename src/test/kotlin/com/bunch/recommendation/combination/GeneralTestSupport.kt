package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.EventTestSupport.ready
import com.bunch.recommendation.combination.PreparationTestSupport.photo
import com.bunch.recommendation.domain.combination.BundleEligibility
import com.bunch.recommendation.domain.combination.DayPreparation
import com.bunch.recommendation.domain.combination.GeneralBundleFinder
import com.bunch.recommendation.domain.combination.GeneralCounter
import com.bunch.recommendation.domain.combination.GeneralResult
import com.bunch.recommendation.domain.combination.GeneralSegment
import com.bunch.recommendation.domain.combination.GeneralSegmenter
import com.bunch.recommendation.domain.combination.RecoPhoto
import java.time.LocalTime

/** What the tests of the general bundles share: photos taken in a row, and the day and the segments of them. */
internal object GeneralTestSupport {
    const val DAY = "2026-10-19"

    /**
     * [count] photos, one every [step] minutes from [from], uploaded by the [members] in turn. With two members or more
     * the members take photos next to each other in time, so the photos have a together moment (3-4).
     */
    fun shots(
        firstId: Long,
        from: String,
        count: Int,
        members: List<Long> = listOf(1, 2),
        step: Long = 10,
        date: String = DAY
    ): List<RecoPhoto> = (0 until count).map { index ->
        photo(
            firstId + index,
            date = date,
            time = LocalTime.parse(from).plusMinutes(step * index).toString(),
            members = listOf(members[index % members.size])
        )
    }

    /** The day of the photos, made ready by the preparation of the days. */
    fun day(photos: List<RecoPhoto>): DayPreparation.Ready = ready(photos)

    fun counter(day: DayPreparation.Ready) = GeneralCounter(BundleEligibility(), day.duplicateGroups)

    /** The segments of the photos of a day, as the segmenter cuts them. */
    fun segments(photos: List<RecoPhoto>): List<GeneralSegment> = GeneralSegmenter().segment(day(photos).photos)

    fun find(photos: List<RecoPhoto>): GeneralResult = GeneralBundleFinder().find(day(photos))

    fun ids(photos: List<RecoPhoto>): List<Long> = photos.map { it.photoId }
}
