package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.PreparationTestSupport.photo
import com.bunch.recommendation.domain.combination.AiAnalysis
import com.bunch.recommendation.domain.combination.DayPreparation
import com.bunch.recommendation.domain.combination.DayPreparer
import com.bunch.recommendation.domain.combination.EventClueCode
import com.bunch.recommendation.domain.combination.PeopleCount
import com.bunch.recommendation.domain.combination.PhotoSubject
import com.bunch.recommendation.domain.combination.Reading
import com.bunch.recommendation.domain.combination.RecoPhoto
import java.time.LocalTime

/** What the tests of the events share: photos with the clues, the people and the subject the AI would have told. */
internal object EventTestSupport {
    const val DAY = "2026-10-19"

    fun analysis(
        vararg clues: EventClueCode,
        confidence: Double = 0.9,
        subject: PhotoSubject? = null,
        people: PeopleCount? = null
    ) = AiAnalysis(clues = clues.map { Reading(it, confidence) }, subject = subject, people = people)

    /** A photo that shows the clues and, when [person] is set, friends as its subject. */
    fun clued(
        id: Long,
        time: String,
        member: Long = 1,
        vararg clues: EventClueCode,
        person: Boolean = false,
        subject: PhotoSubject = PhotoSubject.FOOD,
        date: String = DAY,
        confidence: Double = 0.9
    ): RecoPhoto = photo(
        id,
        date = date,
        time = time,
        members = listOf(member),
        analysis = if (person) {
            analysis(*clues, confidence = confidence, subject = PhotoSubject.PERSON, people = PeopleCount.P3_PLUS)
        } else {
            analysis(*clues, confidence = confidence, subject = subject, people = PeopleCount.P0)
        }
    )

    /** Photos of friends, one every [step] minutes, uploaded by the members in turn. */
    fun friends(
        firstId: Long,
        from: String,
        count: Int,
        members: List<Long> = listOf(1, 2),
        step: Long = 10,
        date: String = DAY
    ): List<RecoPhoto> = (0 until count).map { index ->
        clued(
            firstId + index,
            LocalTime.parse(from).plusMinutes(step * index).toString(),
            members[index % members.size],
            person = true,
            date = date
        )
    }

    /** Photos of a thing that is not a person (a street, the sky), one every [step] minutes. */
    fun things(
        firstId: Long,
        from: String,
        count: Int,
        member: Long = 1,
        step: Long = 10,
        date: String = DAY
    ): List<RecoPhoto> = (0 until count).map { index ->
        clued(
            firstId + index,
            LocalTime.parse(from).plusMinutes(step * index).toString(),
            member,
            subject = PhotoSubject.LANDSCAPE,
            date = date
        )
    }

    /** The day of the photos, made ready by the preparation of the days. */
    fun ready(photos: List<RecoPhoto>): DayPreparation.Ready =
        DayPreparer().prepare(photos).days.single() as DayPreparation.Ready
}
